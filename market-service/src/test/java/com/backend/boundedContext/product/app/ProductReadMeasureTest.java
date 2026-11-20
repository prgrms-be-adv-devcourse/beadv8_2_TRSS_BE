package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.ProductSummaryDto;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 상품 상세·검색 읽기 측정 (DB 검색 설정). 트랜잭션 분리·상세 쿼리 줄이기 전후를 같은 조건으로 잰다.
 * 실행: {@code ./gradlew :market-service:measureTest}. 단언하지 않고 {@code [측정]} 줄만 남긴다.
 * ES가 죽었을 때 검색은 설정이 달라 {@link ProductReadEsDownMeasureTest}가 잰다.
 */
@Tag("measure")
@SpringBootTest   // 쿼리 수는 테스트 설정(application.properties)의 ThreadQueryCounter로 센다
class ProductReadMeasureTest extends ProductReadMeasureSupport {
    /** 회원 서비스 지연 (응답 제한 3초보다 짧게 둔다) */
    private static final long SLOW_MILLIS = 2_000;
    private static final int CONCURRENT_SEARCHES = 20;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("상세 쿼리 수 (비로그인·로그인)")
    void detailQueryCount() {
        record("상세 쿼리 수 (비로그인)", queryCount(() -> productFacade.getProductDetail(detailProductId)));
        record("상세 쿼리 수 (로그인)", queryCount(() -> productFacade.getProductDetail(detailProductId, LOGIN_MEMBER_ID)));
    }

    /** 워밍업 한 번 뒤, 이 스레드에서 나간 쿼리 수와 대상 테이블 (스케줄러 쿼리는 섞이지 않는다) */
    private String queryCount(Runnable action) {
        action.run();
        List<String> sqls = ThreadQueryCounter.record(action);
        sqls.forEach(sql -> log.debug("[측정 SQL] {}", sql));
        return sqls.size() + "번 " + sqls.stream().map(ProductReadMeasureTest::mainTable).toList();
    }

    /** SQL의 첫 from 테이블 (어떤 쿼리가 나갔는지 한눈에 보려는 용도) */
    private static String mainTable(String sql) {
        String lower = sql.toLowerCase();
        int from = lower.indexOf(" from ");
        if (from < 0) return lower.split(" ")[0];
        String rest = lower.substring(from + 6).trim();
        return rest.split("[ ,()]")[0];
    }

    @Test
    @DisplayName("상세·검색 응답 시간 (워밍업 20번 뒤 200번)")
    void latency() {
        record("상세 응답 시간 (비로그인)", summary(latencies(() -> productFacade.getProductDetail(detailProductId), WARMUP, ROUNDS)));
        record("상세 응답 시간 (로그인)", summary(latencies(
                () -> productFacade.getProductDetail(detailProductId, LOGIN_MEMBER_ID), WARMUP, ROUNDS)));
        record("검색 응답 시간 (DB 검색, 20개)", summary(latencies(this::search, WARMUP, ROUNDS)));
    }

    @Test
    @DisplayName("회원 서비스가 2초 걸릴 때: 검색 20건을 동시에 보내는 동안 다른 DB 읽기의 응답 시간과 커넥션 풀 상태")
    void slowMemberHoldsConnections() throws Exception {
        givenSellerSlow(SLOW_MILLIS);
        HikariPoolMXBean pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger maxPending = new AtomicInteger();
        AtomicBoolean sampling = new AtomicBoolean(true);
        Thread sampler = Thread.ofPlatform().start(() -> {
            while (sampling.get()) {
                maxActive.accumulateAndGet(pool.getActiveConnections(), Math::max);
                maxPending.accumulateAndGet(pool.getThreadsAwaitingConnection(), Math::max);
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });

        List<Double> searchTimes = new ArrayList<>();
        double otherRead;
        try (ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_SEARCHES)) {
            List<Future<Double>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_SEARCHES; i++) {
                futures.add(executor.submit(() -> timed(this::search)));
            }
            Thread.sleep(300);   // 검색들이 커넥션을 잡은 뒤에 다른 읽기를 보낸다
            otherRead = timed(() -> productFacade.getProducts(List.of(detailProductId)));
            for (Future<Double> future : futures) searchTimes.add(future.get());
        } finally {
            sampling.set(false);
            sampler.join();
        }
        searchTimes.sort(null);

        record("회원 2초 지연 + 검색 20건 동시: 다른 DB 읽기(getProducts) 응답 시간", "%.1fms".formatted(otherRead));
        record("회원 2초 지연 + 검색 20건 동시: 검색 응답 시간", summary(searchTimes));
        record("회원 2초 지연 + 검색 20건 동시: 커넥션 풀 최대 사용 / 최대 대기 스레드",
                maxActive.get() + "개 (풀 " + pool.getTotalConnections() + "개) / " + maxPending.get() + "개");
    }

    @Test
    @DisplayName("회원 서비스가 실패할 때 검색·상세 결과")
    void memberFailure() {
        givenSellerFails();

        record("회원 서비스 실패 시 검색", outcome(() -> {
            List<ProductSummaryDto> items = search().items();
            return items.size() + "개, 첫 상품 상호명=" + items.getFirst().businessName();
        }));
        record("회원 서비스 실패 시 상세", outcome(() ->
                "상호명=" + productFacade.getProductDetail(detailProductId).businessName()));
    }
}
