package com.backend.boundedContext.product.out.keyword;

import com.backend.boundedContext.product.domain.ProductPolicy;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.out.OrderApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인기 검색어 Redis 어댑터를 실제 Redis(로컬 compose, CI 서비스 컨테이너)로 확인한다.
 * 실제 데이터와 섞이지 않게 2001년 시각의 키와 qa2001- 접두어 key만 쓰고, 테스트마다 지운다. 기록은 바로 실행하는 Executor로 기다리지 않고 확인한다.
 */
@SpringBootTest
class PopularKeywordRedisAdapterTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2001, 1, 2, 10, 30);
    /** 이름 해시(labels)는 시각별로 나뉘지 않으므로 테스트 key에 접두어를 붙여 구분하고, 테스트마다 지운다 */
    private static final String KEY = "qa2001-";
    /** 감쇠가 사실상 없는 반감기(시간). 가중치가 모두 1에 가까워 횟수 합계로 순위를 본다 */
    private static final double NO_DECAY = 1_000_000;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ProductPolicy productPolicy;

    /** 다른 상품 통합 테스트와 mock 조합을 맞춰 테스트 컨텍스트를 같이 쓴다 */
    @MockitoBean
    private MemberApi memberApi;

    @MockitoBean
    private OrderApi orderApi;

    private PopularKeywordRedisAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new PopularKeywordRedisAdapter(redisTemplate, productPolicy, Runnable::run);
        deleteTestKeys();
    }

    @AfterEach
    void tearDown() {
        deleteTestKeys();
    }

    @Test
    @DisplayName("기록: 검색 시각이 속한 시간 키에 key별 횟수를 더하고, 집계 구간 + 2시간 TTL을 건다")
    void increaseCountsInHourKey() {
        adapter.increase(KEY + "수분크림", "수분 크림", BASE);
        adapter.increase(KEY + "수분크림", "수분크림", BASE.plusMinutes(20));   // 같은 10시, 띄어쓰기만 다름
        adapter.increase(KEY + "선크림", "선크림", BASE);

        String hourKey = PopularKeywordRedisAdapter.KEY_PREFIX + "2001010210";
        assertThat(redisTemplate.opsForZSet().score(hourKey, KEY + "수분크림")).isEqualTo(2.0);
        assertThat(redisTemplate.opsForZSet().score(hourKey, KEY + "선크림")).isEqualTo(1.0);
        long windowHours = productPolicy.getPopularKeywordWindowHours();
        assertThat(redisTemplate.getExpire(hourKey)).isBetween((windowHours + 1) * 3600, (windowHours + 2) * 3600);
        assertThat(redisTemplate.getExpire(PopularKeywordRedisAdapter.LABELS_KEY)).isBetween((windowHours + 1) * 3600, (windowHours + 2) * 3600);
    }

    @Test
    @DisplayName("조회: 순위에는 key마다 마지막으로 센 이름을 보여 준다")
    void findTopShowsLatestLabel() {
        adapter.increase(KEY + "수분크림", "수분 크림", BASE);
        adapter.increase(KEY + "수분크림", "수분크림", BASE);
        adapter.increase(KEY + "sun크림", "sun 크림", BASE);

        assertThat(adapter.findTop(BASE, 1, NO_DECAY, 0, 10)).containsExactly("수분크림", "sun 크림");
    }

    @Test
    @DisplayName("조회(감쇠 없음): 구간 안 시간 키들의 합계가 큰 순서이고, 같으면 검색어 순서이며, 구간 밖 키는 넣지 않는다")
    void findTopSumsHoursInWindow() {
        increase("선크림", BASE, 2);
        increase("선크림", BASE.minusHours(2), 2);            // 합계 4
        increase("수분크림", BASE.minusHours(1), 3);           // 3
        increase("토너", BASE.minusHours(1), 3);               // 3 (같은 시간의 수분크림과 점수가 같아 검색어 순서)
        increase("앰플", BASE.minusHours(3), 9);               // 3시간 구간 밖

        assertThat(adapter.findTop(BASE, 3, NO_DECAY, 0, 10)).containsExactly("선크림", "수분크림", "토너");
        assertThat(adapter.findTop(BASE, 3, NO_DECAY, 0, 2)).containsExactly("선크림", "수분크림");
        assertThat(adapter.findTop(BASE, 4, NO_DECAY, 0, 1)).containsExactly("앰플");
    }

    @Test
    @DisplayName("조회(감쇠): 반감기마다 절반으로 세서 최근 검색이 앞서고, 감쇠한 점수가 최소 점수보다 작으면 뺀다")
    void findTopDecaysOlderHours() {
        increase("선크림", BASE.minusHours(6), 8);   // 반감기 3시간: 8 × 0.25 = 2
        increase("립밤", BASE, 3);                   // 3 × 1 = 3
        increase("토너", BASE.minusHours(3), 4);     // 4 × 0.5 = 2 (선크림과 같으면 key 순서)
        increase("앰플", BASE.minusHours(9), 7);     // 7 × 0.125 = 0.875 < 최소 점수 1

        assertThat(adapter.findTop(BASE, 24, 3, 1, 10)).containsExactly("립밤", "선크림", "토너");
        // 감쇠가 없으면 횟수 그대로: 선크림 8, 앰플 7, 토너 4, 립밤 3
        assertThat(adapter.findTop(BASE, 24, NO_DECAY, 1, 10)).containsExactly("선크림", "앰플", "토너", "립밤");
    }

    @Test
    @DisplayName("조회: 기록이 없으면 빈 목록이다")
    void findTopEmpty() {
        assertThat(adapter.findTop(BASE, 24, 3, 1, 10)).isEmpty();
    }

    /** 이름이 key와 같은 검색어를 times번 센다 (key는 다른 테스트 데이터와 섞이지 않게 KEY를 앞에 붙인다) */
    private void increase(String keyword, LocalDateTime at, int times) {
        for (int i = 0; i < times; i++) {
            adapter.increase(KEY + keyword, keyword, at);
        }
    }

    private void deleteTestKeys() {
        Set<String> keys = redisTemplate.keys(PopularKeywordRedisAdapter.KEY_PREFIX + "2001*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        Set<String> labelKeys = redisTemplate.<String, String>opsForHash().keys(PopularKeywordRedisAdapter.LABELS_KEY);
        Object[] testLabelKeys = labelKeys.stream().filter(key -> key.startsWith(KEY)).toArray();
        if (testLabelKeys.length > 0) {
            redisTemplate.opsForHash().delete(PopularKeywordRedisAdapter.LABELS_KEY, testLabelKeys);
        }
    }
}
