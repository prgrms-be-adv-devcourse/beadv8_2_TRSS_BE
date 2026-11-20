package com.backend.boundedContext.delivery.app;

import static com.backend.boundedContext.delivery.DeliveryFixtures.paidEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.boundedContext.delivery.FakeDeliveryTracker;
import com.backend.boundedContext.delivery.domain.Courier;
import com.backend.boundedContext.delivery.out.DeliveryRepository;
import com.backend.boundedContext.delivery.out.TrackResult;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.global.exception.DomainException;
import com.backend.shared.delivery.event.DeliveryCompletedEvent;

/**
 * [QA] TRSS-249 관리자 수동 배송 완료 보강 (실제 DB, 가짜 Delivery Tracker).
 * - 두 관리자가 동시에 수동 완료 → 하나만 성공(나머지 409), 이벤트 1건
 * - 수동 완료와 확인 워커가 실제로 같은 행 잠금을 기다리다 동시에 풀려도 완료·이벤트 1건
 * - 이벤트 저장 실패 → 배송 상태·사유도 롤백(SHIPPED 유지, outbox 0행)
 * - 수동 완료된 배송은 콜백 확인 요청·06:00 안전망·웹훅 등록/재등록/활성 수 집계 대상에서 빠진다
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")
@Import(FakeDeliveryTracker.Config.class)
class DeliveryManualCompleteQaIntegrationTest {

    private static final String REASON = "QA 수동 완료 사유";

    @Autowired private DeliveryFacade deliveryFacade;
    @Autowired private DeliveryRepository deliveryRepository;
    @Autowired private FakeDeliveryTracker tracker;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @MockitoSpyBean private EventPublisher eventPublisher;

    private final long base = ThreadLocalRandom.current().nextLong(6_000_000_000L, 7_000_000_000L);
    private final List<Long> orderIds = new ArrayList<>();
    private int seq;

    @BeforeEach
    void setUp() {
        tracker.reset();
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

    @RepeatedTest(3)
    @DisplayName("두 관리자가 같은 배송을 동시에 수동 완료하면 하나만 성공하고 나머지는 409, 이벤트는 1건")
    void twoAdmins_concurrent_onlyOne() throws Exception {
        long id = shipped();
        LocalDateTime deliveredAt = LocalDateTime.now().minusHours(1).truncatedTo(ChronoUnit.SECONDS);

        List<Object> results = raceWhileLocked(id,
                () -> deliveryFacade.completeManually(id, deliveredAt, REASON + "-A"),
                () -> deliveryFacade.completeManually(id, deliveredAt, REASON + "-B"));

        long ok = results.stream().filter(r -> !(r instanceof Throwable)).count();
        List<String> errors = results.stream().filter(r -> r instanceof DomainException)
                .map(r -> ((DomainException) r).getResultCode()).toList();
        assertThat(ok).isEqualTo(1);
        assertThat(errors).containsExactly("409-DELIVERY_STATE_INVALID");
        assertThat(completedEvents(id)).hasSize(1);
        assertThat(row(id).get("status")).isEqualTo("DELIVERED");
    }

    @RepeatedTest(3)
    @DisplayName("수동 완료와 확인 워커가 같은 행 잠금을 기다리다 함께 풀려도 완료·이벤트는 1건, deliveredAt은 이긴 쪽 값과 이벤트가 같다")
    void manualAndWorker_concurrent_onlyOneEvent() throws Exception {
        long id = shipped();
        jdbcTemplate.update("update delivery set tracking_check_requested_at = ? where id = ?", LocalDateTime.now().minusSeconds(5), id);
        LocalDateTime manualAt = LocalDateTime.now().minusHours(2).truncatedTo(ChronoUnit.SECONDS);
        tracker.onTrack(trackingOf(id), () -> TrackResult.of("DELIVERED", OffsetDateTime.now().minusMinutes(1)));

        List<Object> results = raceWhileLocked(id,
                () -> deliveryFacade.completeManually(id, manualAt, REASON),
                () -> deliveryFacade.checkTracking(id, LocalDateTime.now()));

        assertThat(completedEvents(id)).hasSize(1);
        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("DELIVERED");
        boolean manualWon = !(results.get(0) instanceof Throwable);
        if (manualWon) {
            assertThat(results.get(1)).isEqualTo(DeliveryTaskResult.SKIPPED);
            assertThat(row.get("manual_completion_reason")).isEqualTo(REASON);
            assertThat(((Timestamp) row.get("delivered_at")).toLocalDateTime()).isEqualTo(manualAt);
        } else {
            assertThat(((DomainException) results.get(0)).getResultCode()).isEqualTo("409-DELIVERY_STATE_INVALID");
            assertThat(results.get(1)).isEqualTo(DeliveryTaskResult.DONE);
            assertThat(row.get("manual_completion_reason")).isNull();
        }
    }

    @Test
    @DisplayName("이벤트 저장이 실패하면 수동 완료 전체가 롤백된다 (SHIPPED·사유 없음·outbox 0행)")
    void publishFails_rollsBack() {
        long id = shipped();
        doThrow(new IllegalStateException("outbox down")).when(eventPublisher).publish(any(DeliveryCompletedEvent.class));

        assertThatThrownBy(() -> deliveryFacade.completeManually(id, LocalDateTime.now().minusMinutes(5), REASON))
                .isInstanceOf(IllegalStateException.class);

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("SHIPPED");
        assertThat(row.get("delivered_at")).isNull();
        assertThat(row.get("manual_completion_reason")).isNull();
        assertThat(row.get("manually_completed_at")).isNull();
        assertThat(completedEvents(id)).isEmpty();
    }

    @Test
    @DisplayName("수동 완료된 배송은 콜백 확인 요청·안전망·확인 워커·웹훅 등록/재등록/활성 수 대상에서 빠진다")
    void afterManual_otherSchedulersIgnore() {
        long id = shipped();
        LocalDateTime now = LocalDateTime.now();
        // 웹훅이 1시간 뒤 만료되도록 등록돼 있던 상태
        jdbcTemplate.update("update delivery set webhook_expires_at = ? where id = ?", now.plusHours(1), id);
        long activeBefore = deliveryRepository.countActiveWebhooks(now);

        deliveryFacade.completeManually(id, now.minusMinutes(30), REASON);

        assertThat(row(id).get("tracking_check_requested_at")).isNull();
        // 웹훅 만료 시각은 그대로 남지만 SHIPPED 조건으로 집계·재등록에서 빠진다
        assertThat(row(id).get("webhook_expires_at")).isNotNull();
        assertThat(deliveryRepository.countActiveWebhooks(now)).isEqualTo(activeBefore - 1);

        Integer callback = transactionTemplate.execute(tx ->
                deliveryRepository.requestTrackingCheck(Courier.CJ, trackingOf(id), now));
        assertThat(callback).isZero();
        transactionTemplate.executeWithoutResult(tx -> deliveryRepository.requestSafetyCheck(now.plusDays(1), now));
        assertThat(row(id).get("tracking_check_requested_at")).isNull();
        Integer marked = transactionTemplate.execute(tx ->
                deliveryRepository.markWebhookRegistered(id, Courier.CJ, trackingOf(id), now.plusHours(48)));
        assertThat(marked).isZero();

        assertThat(deliveryRepository.findTrackingCheckTargetIds(id - 1, Limit.of(10))).doesNotContain(id);
        assertThat(deliveryRepository.findWebhookRegisterTargetIds(id - 1, Limit.of(10))).doesNotContain(id);
        assertThat(deliveryRepository.findWebhookRenewTargetIds(now.plusDays(3), id - 1, Limit.of(10))).doesNotContain(id);

        assertThat(deliveryFacade.registerWebhook(id, now)).isEqualTo(DeliveryTaskResult.SKIPPED);
        assertThat(tracker.webhookCalls()).isEmpty();
        assertThat(completedEvents(id)).hasSize(1);
    }

    // ---- 도움 메서드 ----

    /**
     * 다른 트랜잭션이 배송 행을 잠근 상태에서 두 작업을 시작하고, 둘 다 잠금을 기다리는 것을 확인한 뒤 잠금을 푼다.
     * 결과는 [first 결과 또는 예외, second 결과 또는 예외]
     */
    private List<Object> raceWhileLocked(long id, ThrowingSupplier first, ThrowingSupplier second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> holder = pool.submit(() -> transactionTemplate.executeWithoutResult(tx -> {
                deliveryRepository.findByIdForUpdate(id).orElseThrow();
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Object> f1 = pool.submit(() -> call(first));
            Future<Object> f2 = pool.submit(() -> call(second));
            waitForLockWaiters(2);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            return List.of(f1.get(10, TimeUnit.SECONDS), f2.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static Object call(ThrowingSupplier supplier) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            return t;
        }
    }

    /** 이 DB에서 행 잠금을 기다리는 세션이 n개 이상이 될 때까지(최대 5초) 기다린다 */
    private void waitForLockWaiters(int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    select count(*) from pg_stat_activity
                    where datname = current_database() and wait_event_type = 'Lock'""", Integer.class);
            if (waiting != null && waiting >= n) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("두 작업이 행 잠금을 기다리지 않았습니다");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        Object get() throws Exception;
    }

    /** READY 배송을 만들고 CJ 송장을 등록해 SHIPPED로 만든다. 발송 시각은 3일 전으로 옮긴다 */
    private long shipped() {
        long orderId = base + seq++;
        orderIds.add(orderId);
        deliveryFacade.createFromPaidOrder(paidEvent(orderId, orderId * 10));
        long id = deliveryRepository.findByOrderId(orderId).orElseThrow().getId();
        deliveryFacade.ship(id, Courier.CJ, String.valueOf(orderId) + "00");
        jdbcTemplate.update("update delivery set shipped_at = ? where id = ?", LocalDateTime.now().minusDays(3), id);
        return id;
    }

    private String trackingOf(long id) {
        return (String) row(id).get("tracking_number");
    }

    private Map<String, Object> row(long id) {
        return jdbcTemplate.queryForMap("select * from delivery where id = ?", id);
    }

    private List<Map<String, Object>> completedEvents(long id) {
        return jdbcTemplate.queryForList("""
                select payload::text as payload from outbox_event
                where event_type = 'DeliveryCompletedEvent' and aggregate_id = ?""", id);
    }
}
