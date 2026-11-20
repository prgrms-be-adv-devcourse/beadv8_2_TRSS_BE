package com.backend.boundedContext.delivery.app;

import static com.backend.boundedContext.delivery.DeliveryFixtures.paidEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.backend.boundedContext.delivery.FakeDeliveryTracker;
import com.backend.boundedContext.delivery.domain.Courier;
import com.backend.boundedContext.delivery.out.DeliveryRepository;
import com.backend.boundedContext.delivery.out.DeliveryTrackerUnavailableException;
import com.backend.boundedContext.delivery.out.TrackResult;
import com.backend.global.eventPublisher.EventPublisher;

/**
 * [QA] 확인 워커·웹훅 등록의 트랜잭션 경계, 동시 실행, outbox 롤백, 호출 실패 시 요청 시각 처리.
 * 실제 DB(로컬 Docker PostgreSQL) + 가짜 Delivery Tracker. 실제 API는 부르지 않는다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")
@Import(FakeDeliveryTracker.Config.class)
class DeliveryTrackingQaIntegrationTest {

    @Autowired private DeliveryFacade deliveryFacade;
    @Autowired private DeliveryRepository deliveryRepository;
    @Autowired private FakeDeliveryTracker tracker;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private EventPublisher eventPublisher;

    private final long base = ThreadLocalRandom.current().nextLong(5_000_000_000L, 6_000_000_000L);
    private final List<Long> orderIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tracker.reset();
        reset(eventPublisher);
    }

    @AfterEach
    void cleanUp() {
        for (Long orderId : orderIds) {
            jdbcTemplate.update("""
                    delete from outbox_event where aggregate_type = 'DELIVERY'
                    and aggregate_id in (select id from delivery where order_id = ?)""", orderId);
            jdbcTemplate.update("delete from delivery_item where delivery_id in (select id from delivery where order_id = ?)", orderId);
            jdbcTemplate.update("delete from delivery where order_id = ?", orderId);
        }
    }

    @Test
    @DisplayName("[QA] track·registerWebhook은 트랜잭션 밖에서 불린다 (DB 커넥션·행 잠금을 잡지 않음)")
    void externalCallsOutsideTransaction() {
        long checkId = shippedWithRequest(0);
        long webhookId = shipped(1);
        AtomicBoolean txDuringTrack = new AtomicBoolean(true);
        AtomicBoolean txDuringRegister = new AtomicBoolean(true);
        tracker.onTrack(tracking(0), () -> {
            txDuringTrack.set(TransactionSynchronizationManager.isActualTransactionActive());
            return TrackResult.of("IN_TRANSIT", OffsetDateTime.now());
        });
        tracker.onRegisterWebhook(tracking(1), () -> {
            txDuringRegister.set(TransactionSynchronizationManager.isActualTransactionActive());
            return true;
        });

        deliveryFacade.checkTracking(checkId, LocalDateTime.now());
        deliveryFacade.registerWebhook(webhookId, LocalDateTime.now());

        assertThat(txDuringTrack).isFalse();
        assertThat(txDuringRegister).isFalse();
    }

    @Test
    @DisplayName("[QA] 조회 중에도 다른 트랜잭션이 같은 행을 바로 잠글 수 있다 (track 동안 행 잠금 없음)")
    void noRowLockDuringTrack() {
        long id = shippedWithRequest(0);
        AtomicBoolean updatedDuringTrack = new AtomicBoolean(false);
        tracker.onTrack(tracking(0), () -> {
            // 다른 커넥션에서 잠금 대기 1초 제한으로 UPDATE. 잠겨 있으면 예외
            ExecutorService other = Executors.newSingleThreadExecutor();
            try {
                Future<Integer> f = other.submit(() -> jdbcTemplate.execute((java.sql.Connection c) -> {
                    try (var st = c.createStatement()) {
                        st.execute("set lock_timeout = '1s'");
                        return st.executeUpdate("update delivery set last_tracking_status = last_tracking_status where id = " + id);
                    }
                }));
                updatedDuringTrack.set(f.get(5, TimeUnit.SECONDS) == 1);
            } catch (Exception e) {
                updatedDuringTrack.set(false);
            } finally {
                other.shutdownNow();
            }
            return TrackResult.of("IN_TRANSIT", OffsetDateTime.now());
        });

        deliveryFacade.checkTracking(id, LocalDateTime.now());

        assertThat(updatedDuringTrack).isTrue();
    }

    @Test
    @DisplayName("[QA] 두 워커가 같은 건을 동시에 DELIVERED로 확인 → DONE 1건·SKIPPED 1건, DeliveryCompletedEvent 1건")
    void concurrentWorkers_oneEvent() throws Exception {
        long id = shippedWithRequest(0);
        CountDownLatch bothTracking = new CountDownLatch(2);
        tracker.onTrack(tracking(0), () -> {
            bothTracking.countDown();
            try {
                bothTracking.await(5, TimeUnit.SECONDS);   // 두 스레드가 모두 SHIPPED 스냅샷을 읽은 뒤 쓰기로 간다
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return TrackResult.of("DELIVERED", OffsetDateTime.now().minusMinutes(5));
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            LocalDateTime startedAt = LocalDateTime.now();
            Future<DeliveryTaskResult> a = pool.submit(() -> deliveryFacade.checkTracking(id, startedAt));
            Future<DeliveryTaskResult> b = pool.submit(() -> deliveryFacade.checkTracking(id, startedAt));
            List<DeliveryTaskResult> results = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));

            assertThat(results).containsExactlyInAnyOrder(DeliveryTaskResult.DONE, DeliveryTaskResult.SKIPPED);
        } finally {
            pool.shutdownNow();
        }
        assertThat(row(id).get("status")).isEqualTo("DELIVERED");
        assertThat(completedEventCount(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("[QA] outbox 저장이 실패하면 배송 완료도 롤백 → SHIPPED·요청 시각 유지·outbox 0건·lastTrackingStatus 그대로")
    void outboxFailure_rollsBack() {
        long id = shippedWithRequest(0);
        tracker.onTrack(tracking(0), () -> TrackResult.of("DELIVERED", OffsetDateTime.now().minusMinutes(5)));
        doThrow(new IllegalStateException("outbox 저장 실패")).when(eventPublisher).publish(any());

        assertThatThrownBy(() -> deliveryFacade.checkTracking(id, LocalDateTime.now()))
                .isInstanceOf(IllegalStateException.class);

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("SHIPPED");
        assertThat(row.get("delivered_at")).isNull();
        assertThat(row.get("last_tracking_status")).isNull();
        assertThat(row.get("tracking_check_requested_at")).isNotNull();
        assertThat(completedEventCount(id)).isZero();
    }

    @Test
    @DisplayName("[QA] 호출 실패(Unavailable) → 예외를 그대로 던지고 요청 시각·상태는 그대로 (다음 회차에 다시)")
    void unavailable_keepsRequest() {
        long id = shippedWithRequest(0);
        Object before = row(id).get("tracking_check_requested_at");
        tracker.onTrack(tracking(0), () -> { throw new DeliveryTrackerUnavailableException("timeout"); });

        assertThatThrownBy(() -> deliveryFacade.checkTracking(id, LocalDateTime.now()))
                .isInstanceOf(DeliveryTrackerUnavailableException.class);

        assertThat(row(id).get("tracking_check_requested_at")).isEqualTo(before);
        assertThat(row(id).get("status")).isEqualTo("SHIPPED");
    }

    @Test
    @DisplayName("[QA] DELIVERED·READY 배송에 온 콜백 → 0건, 요청 시각 변화 없음")
    void callbackOnNonShipped_noChange() {
        long delivered = shippedWithRequest(0);
        tracker.onTrack(tracking(0), () -> TrackResult.of("DELIVERED", OffsetDateTime.now().minusMinutes(5)));
        deliveryFacade.checkTracking(delivered, LocalDateTime.now());

        assertThat(deliveryFacade.requestTrackingCheck("kr.cjlogistics", tracking(0))).isZero();
        assertThat(row(delivered).get("tracking_check_requested_at")).isNull();
    }

    @Test
    @DisplayName("[QA] 송장 수정 후 옛 송장으로 온 콜백 → 0건, 새 송장 콜백 → 1건")
    void callbackAfterTrackingChange() {
        long id = shipped(0);
        deliveryFacade.changeTracking(id, Courier.CJ, tracking(9));

        assertThat(deliveryFacade.requestTrackingCheck("kr.cjlogistics", tracking(0))).isZero();
        assertThat(deliveryFacade.requestTrackingCheck("kr.cjlogistics", tracking(9))).isEqualTo(1);
    }

    // ---- 도움 메서드 ----

    private String tracking(int n) {
        return base + String.format("%02d", n);
    }

    private long shipped(int n) {
        long orderId = base + n;
        orderIds.add(orderId);
        deliveryFacade.createFromPaidOrder(paidEvent(orderId, orderId * 10));
        long id = deliveryRepository.findByOrderId(orderId).orElseThrow().getId();
        deliveryFacade.ship(id, Courier.CJ, tracking(n));
        return id;
    }

    private long shippedWithRequest(int n) {
        long id = shipped(n);
        jdbcTemplate.update("update delivery set tracking_check_requested_at = ? where id = ?", LocalDateTime.now().minusSeconds(5), id);
        return id;
    }

    private Map<String, Object> row(long id) {
        return jdbcTemplate.queryForMap("select * from delivery where id = ?", id);
    }

    private int completedEventCount(long id) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from outbox_event where event_type = 'DeliveryCompletedEvent' and aggregate_id = ?", Integer.class, id);
        return count == null ? 0 : count;
    }
}
