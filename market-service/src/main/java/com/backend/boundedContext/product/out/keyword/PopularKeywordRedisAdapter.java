package com.backend.boundedContext.product.out.keyword;

import com.backend.boundedContext.product.domain.ProductPolicy;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.zset.Aggregate;
import org.springframework.data.redis.connection.zset.Weights;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/**
 * 인기 검색어를 Redis ZSET에 시간 단위로 센다. 키는 product:popular-keyword:{yyyyMMddHH}, 멤버는 세는 key(띄어쓰기 없앤 검색어), 점수는 횟수다.
 * 순위에 보여 줄 이름(label)은 해시 product:popular-keyword:labels에 key별로 마지막 것을 둔다.
 * <p>
 * 기록: 시간 키에 ZINCRBY 하고 TTL을 건다. TTL은 집계 구간 + 2시간이다
 * (한 시간 전 기준 순위를 비교할 때 구간보다 한 시간 앞 키까지 읽고, 경계에서 지워지지 않게 한 시간을 더 둔다).
 * 기록은 요청 스레드가 아니라 전용 스레드 하나에서 차례로 하므로 Redis가 느리거나 꺼져 있어도 검색 응답은 늦어지지 않는다.
 * 대기열이 넘치거나 실패하면 그 검색은 세지 않는다 (인기 검색어는 근사치라 괜찮다).
 * <p>
 * 조회: 시간 키들을 ZUNION WEIGHTS로 합친다(Redis 6.2+). 시간마다 2^(-지난 시간/반감기)를 곱해, 최근 검색일수록 크게 센다.
 * 결과를 키로 저장하지 않아 임시 키를 지울 일이 없다.
 */
@Slf4j
@Component
public class PopularKeywordRedisAdapter implements PopularKeywordPort {
    /** 테스트에서 키를 직접 확인하거나 지울 때도 이 접두어를 쓴다 */
    public static final String KEY_PREFIX = "product:popular-keyword:";
    /** 세는 key → 보여 줄 이름. 시간 키처럼 TTL을 걸어, 한동안 검색되지 않은 이름은 함께 사라진다 */
    public static final String LABELS_KEY = KEY_PREFIX + "labels";
    private static final DateTimeFormatter HOUR_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHH");
    /** 기록을 기다리는 요청 최대 수. 넘치면 버린다 (Redis가 오래 멈춘 경우) */
    private static final int QUEUE_CAPACITY = 1_000;

    private final StringRedisTemplate redisTemplate;
    private final ProductPolicy productPolicy;
    private final Executor executor;

    @Autowired
    public PopularKeywordRedisAdapter(StringRedisTemplate redisTemplate, ProductPolicy productPolicy) {
        this(redisTemplate, productPolicy, new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "product-popular-keyword");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    /** 테스트에서 바로 실행하는 Executor를 넣는다 */
    PopularKeywordRedisAdapter(StringRedisTemplate redisTemplate, ProductPolicy productPolicy, Executor executor) {
        this.redisTemplate = redisTemplate;
        this.productPolicy = productPolicy;
        this.executor = executor;
    }

    @Override
    public void increase(String key, String label, LocalDateTime at) {
        try {
            executor.execute(() -> write(key, label, at));
        } catch (RejectedExecutionException e) {
            log.debug("인기 검색어 기록 대기열이 가득 차 이번 검색은 세지 않습니다.");
        }
    }

    private void write(String key, String label, LocalDateTime at) {
        String hourKey = key(at);
        Duration ttl = Duration.ofHours(productPolicy.getPopularKeywordWindowHours() + 2L);
        try {
            redisTemplate.opsForZSet().incrementScore(hourKey, key, 1);
            redisTemplate.expire(hourKey, ttl);
            redisTemplate.opsForHash().put(LABELS_KEY, key, label);
            redisTemplate.expire(LABELS_KEY, ttl);
        } catch (Exception e) {
            log.warn("인기 검색어를 기록하지 못했습니다. 이번 검색은 세지 않습니다: {}", e.getMessage());
        }
    }

    @Override
    public List<String> findTop(LocalDateTime lastHour, int hours, double halfLifeHours, double minScore, int limit) {
        List<String> keys = IntStream.range(0, hours)
                .mapToObj(i -> key(lastHour.minusHours(i)))
                .toList();
        Weights weights = Weights.of(IntStream.range(0, hours)
                .mapToDouble(i -> Math.pow(2, -i / halfLifeHours))
                .toArray());
        Set<TypedTuple<String>> union = redisTemplate.opsForZSet()
                .unionWithScores(keys.getFirst(), keys.subList(1, keys.size()), Aggregate.SUM, weights);
        if (union == null) return List.of();

        List<String> topKeys = union.stream()
                .filter(tuple -> tuple.getValue() != null && tuple.getScore() != null)
                .filter(tuple -> tuple.getScore() >= minScore)
                .sorted(Comparator.comparing((TypedTuple<String> tuple) -> tuple.getScore()).reversed()
                        .thenComparing(TypedTuple::getValue))
                .limit(limit)
                .map(TypedTuple::getValue)
                .toList();
        if (topKeys.isEmpty()) return List.of();

        HashOperations<String, String, String> labels = redisTemplate.opsForHash();
        List<String> found = labels.multiGet(LABELS_KEY, topKeys);
        return IntStream.range(0, topKeys.size())
                .mapToObj(i -> found == null || found.get(i) == null ? topKeys.get(i) : found.get(i))
                .toList();
    }

    static String key(LocalDateTime at) {
        return KEY_PREFIX + at.format(HOUR_FORMAT);
    }

    @PreDestroy
    void shutdown() {
        if (executor instanceof ExecutorService service) {
            service.shutdown();
        }
    }
}
