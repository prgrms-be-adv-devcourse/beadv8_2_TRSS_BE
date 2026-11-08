package com.backend.boundedContext.delivery.app;

import static com.backend.boundedContext.delivery.DeliveryFixtures.item;
import static com.backend.boundedContext.delivery.DeliveryFixtures.paidEvent;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.boundedContext.delivery.domain.Courier;
import com.backend.boundedContext.delivery.in.dto.LogisticsDeliveryResponse;
import com.backend.boundedContext.delivery.out.DeliveryRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.page.PageResponse;
import com.backend.shared.delivery.dto.DeliveryStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

/**
 * (QA 추가) 송장 등록 동시성과 목록 N+1을 실제 DB(로컬 Docker PostgreSQL, delivery 스키마)로 확인한다.
 * - 같은 배송 건에 두 스레드가 동시에 송장 등록 → 하나만 성공, 다른 하나는 409 DELIVERY_STATE_INVALID (행 잠금)
 * - 서로 다른 배송 건에 같은 송장을 동시에 등록 → DB 유니크(uk_delivery_tracking)가 막아 DataIntegrityViolationException
 *   (GlobalExceptionHandler가 409 DATA_CONFLICT로 응답)
 * - 목록 조회는 항목을 @BatchSize로 한 번에 읽는다(배송 건 수만큼 쿼리가 늘지 않는다)
 */
@SpringBootTest(properties = {
        "custom.scheduling.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
class DeliveryConcurrencyIntegrationTest {

    @Autowired private DeliveryFacade deliveryFacade;
    @Autowired private DeliveryRepository deliveryRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private EntityManager entityManager;
    @Autowired private EntityManagerFactory entityManagerFactory;

    // 다른 테스트·로컬 데이터와 겹치지 않는 주문·주문 항목 ID 범위
    private final long base = ThreadLocalRandom.current().nextLong(6_000_000_000L, 7_000_000_000L);
    private final List<Long> orderIds = new ArrayList<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void cleanUp() {
        executor.shutdownNow();
        for (Long orderId : orderIds) {
            jdbcTemplate.update("delete from delivery_item where delivery_id in (select id from delivery where order_id = ?)", orderId);
            jdbcTemplate.update("delete from delivery where order_id = ?", orderId);
        }
    }

    @Test
    @DisplayName("같은 배송 건에 두 스레드가 동시에 송장 등록 → 하나만 SHIPPED, 다른 하나는 409 DELIVERY_STATE_INVALID")
    void ship_sameDeliveryConcurrently_oneWins() throws Exception {
        long deliveryId = deliveryIdOf(create(0));
        CountDownLatch start = new CountDownLatch(1);

        Future<Object> first = executor.submit(shipAfter(start, deliveryId, tracking(1)));
        Future<Object> second = executor.submit(shipAfter(start, deliveryId, tracking(2)));
        start.countDown();

        List<Object> results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        assertThat(results).filteredOn(r -> r instanceof LogisticsDeliveryResponse).hasSize(1);
        assertThat(results).filteredOn(r -> r instanceof DomainException e
                && "409-DELIVERY_STATE_INVALID".equals(e.getResultCode())).hasSize(1);

        LogisticsDeliveryResponse saved = deliveryFacade.getDelivery(deliveryId);
        assertThat(saved.status()).isEqualTo(DeliveryStatus.SHIPPED);
        LogisticsDeliveryResponse winner = (LogisticsDeliveryResponse) results.stream()
                .filter(r -> r instanceof LogisticsDeliveryResponse).findFirst().orElseThrow();
        assertThat(saved.trackingNumber()).isEqualTo(winner.trackingNumber());
    }

    @Test
    @DisplayName("다른 배송 건에 같은 송장을 동시에 등록(사전 검사를 둘 다 통과) → 늦게 커밋한 쪽은 DB 유니크로 DataIntegrityViolationException, 한 건만 남는다")
    void ship_sameTrackingOnTwoDeliveriesConcurrently_dbUniqueBlocks() throws Exception {
        long a = deliveryIdOf(create(0));
        long b = deliveryIdOf(create(1));
        String trackingNumber = tracking(5);
        CountDownLatch aFlushed = new CountDownLatch(1);

        // A: 송장 등록을 flush까지 하고 커밋을 미룬다(유니크 인덱스에 미커밋 값이 들어간 상태)
        Future<Object> first = executor.submit(() -> {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    deliveryFacade.ship(a, Courier.CJ, trackingNumber);
                    entityManager.flush();
                    aFlushed.countDown();
                    sleep(1_500);   // B가 사전 검사를 통과하고 커밋에서 유니크 인덱스를 기다리게 한다
                });
                return "committed";
            } catch (Exception e) {
                aFlushed.countDown();
                return e;
            }
        });
        // B: A의 미커밋 값은 보이지 않으므로 사전 검사(existsBy...)를 통과한다 → 커밋에서 유니크 위반
        Future<Object> second = executor.submit(() -> {
            aFlushed.await(10, TimeUnit.SECONDS);
            try {
                return deliveryFacade.ship(b, Courier.CJ, trackingNumber);
            } catch (Exception e) {
                return e;
            }
        });

        assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo("committed");
        assertThat(second.get(30, TimeUnit.SECONDS)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from delivery where courier = 'CJ' and tracking_number = ?", Integer.class, trackingNumber))
                .isEqualTo(1);
        assertThat(deliveryFacade.getDelivery(a).status()).isEqualTo(DeliveryStatus.SHIPPED);
        assertThat(deliveryFacade.getDelivery(b).status()).isEqualTo(DeliveryStatus.READY);
    }

    @Test
    @DisplayName("다른 배송 건에 같은 송장을 두 스레드가 동시에 등록 → 하나만 성공, 다른 하나는 409(DELIVERY_TRACKING_DUPLICATED 또는 DB 유니크)")
    void ship_sameTrackingRace_oneWins() throws Exception {
        long a = deliveryIdOf(create(0));
        long b = deliveryIdOf(create(1));
        String trackingNumber = tracking(7);
        CountDownLatch start = new CountDownLatch(1);

        Future<Object> first = executor.submit(shipAfter(start, a, trackingNumber));
        Future<Object> second = executor.submit(shipAfter(start, b, trackingNumber));
        start.countDown();

        List<Object> results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        assertThat(results).filteredOn(r -> r instanceof LogisticsDeliveryResponse).hasSize(1);
        assertThat(results).filteredOn(r -> r instanceof DataIntegrityViolationException
                || (r instanceof DomainException e && "409-DELIVERY_TRACKING_DUPLICATED".equals(e.getResultCode())))
                .hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from delivery where courier = 'CJ' and tracking_number = ?", Integer.class, trackingNumber))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("목록 조회는 배송 건 수와 상관없이 쿼리 수가 일정하다(항목 @BatchSize, N+1 없음)")
    void getDeliveries_noNPlusOne() {
        LocalDateTime old = LocalDateTime.of(1999, 1, 1, 0, 0);   // 로컬 데이터보다 앞에 오게
        for (int n = 0; n < 5; n++) {
            create(n, old.plusMinutes(n));
        }
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        PageResponse<LogisticsDeliveryResponse> page = deliveryFacade.getDeliveries(DeliveryStatus.READY, PageRequest.of(0, 100));

        assertThat(page.items()).hasSizeGreaterThanOrEqualTo(5);
        assertThat(page.items().subList(0, 5)).allMatch(d -> d.items().size() == 3);
        // 배송 목록 1 + (페이지가 꽉 차면) count 1 + 항목 일괄 조회 1
        // 통계가 꺼져 있으면 0이라 그냥 통과하므로 하한도 둔다(목록 + 항목 최소 2)
        assertThat(statistics.getPrepareStatementCount()).isBetween(2L, 3L);
    }

    private Callable<Object> shipAfter(CountDownLatch start, long deliveryId, String trackingNumber) {
        return () -> {
            start.await(10, TimeUnit.SECONDS);
            try {
                return deliveryFacade.ship(deliveryId, Courier.CJ, trackingNumber);
            } catch (Exception e) {
                return e;
            }
        };
    }

    private long create(int n) {
        return create(n, LocalDateTime.now());
    }

    private long create(int n, LocalDateTime paidAt) {
        long orderId = base + n;
        orderIds.add(orderId);
        deliveryFacade.createFromPaidOrder(paidEvent(orderId, paidAt, List.of(
                item(base * 10 + n * 10L, 1L, 1),
                item(base * 10 + n * 10L + 1, 1L, 2),
                item(base * 10 + n * 10L + 2, 2L, 3))));
        return orderId;
    }

    private long deliveryIdOf(long orderId) {
        return deliveryRepository.findByOrderId(orderId).orElseThrow().getId();
    }

    // 이 테스트 전용 송장번호 (11자, base로 다른 실행과 겹치지 않게)
    private String tracking(int n) {
        return String.valueOf(base) + n;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
