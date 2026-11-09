package com.backend.boundedContext.delivery.app;

import static com.backend.boundedContext.delivery.DeliveryFixtures.paidEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.backend.boundedContext.delivery.FakeDeliveryTracker;
import com.backend.boundedContext.delivery.domain.Courier;
import com.backend.boundedContext.delivery.domain.DeliveryTrackerPolicy;
import com.backend.boundedContext.delivery.out.DeliveryRepository;
import com.backend.boundedContext.delivery.out.DeliveryTrackerBadRequestException;
import com.backend.boundedContext.delivery.out.DeliveryTrackerQuotaExceededException;
import com.backend.boundedContext.delivery.out.DeliveryTrackerUnauthenticatedException;
import com.backend.boundedContext.delivery.out.TrackResult;

/**
 * 확인 워커·웹훅 등록·안전망을 실제 DB(로컬 Docker PostgreSQL, delivery 스키마)와 가짜 Delivery Tracker로 확인한다.
 * 실제 Delivery Tracker API는 부르지 않는다. 다른 테스트·로컬 데이터와 겹치지 않게 큰 주문 ID를 쓰고 끝나면 지운다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")
@Import(FakeDeliveryTracker.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class DeliveryTrackingIntegrationTest {

    @Autowired private DeliveryFacade deliveryFacade;
    @Autowired private DeliveryRepository deliveryRepository;
    @Autowired private DeliveryTrackerPolicy deliveryTrackerPolicy;
    @Autowired private FakeDeliveryTracker tracker;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final long base = ThreadLocalRandom.current().nextLong(4_000_000_000L, 5_000_000_000L);
    private final List<Long> orderIds = new ArrayList<>();

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

    // ---- 확인 워커 ----

    @Test
    @DisplayName("DELIVERED + time 2026-11-05T01:00Z → DELIVERED, deliveredAt 10:00(KST), DeliveryCompletedEvent 1건, 요청 시각 null")
    void delivered(CapturedOutput output) {
        long id = shippedWithRequest(0);
        tracker.onTrack(tracking(0), () -> TrackResult.of("DELIVERED", OffsetDateTime.of(2026, 11, 5, 1, 0, 0, 0, ZoneOffset.UTC)));

        DeliveryTaskResult result = deliveryFacade.checkTracking(id, LocalDateTime.now());

        assertThat(result).isEqualTo(DeliveryTaskResult.DONE);
        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("DELIVERED");
        assertThat(((Timestamp) row.get("delivered_at")).toLocalDateTime()).isEqualTo(LocalDateTime.of(2026, 11, 5, 10, 0));
        assertThat(row.get("last_tracking_status")).isEqualTo("DELIVERED");
        assertThat(row.get("tracking_check_requested_at")).isNull();

        List<Map<String, Object>> events = completedEvents(id);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("aggregate_type")).isEqualTo("DELIVERY");
        assertThat((String) events.get(0).get("payload")).contains("\"orderId\": " + orderId(0)).contains("2026-11-05T10:00");
        assertThat(output).contains("배송 완료: deliveryId=" + id);
    }

    @Test
    @DisplayName("time 없음 / 미래 → deliveredAt = 확인 시각")
    void delivered_noOrFutureTime() {
        long noTime = shippedWithRequest(0);
        long future = shippedWithRequest(1);
        tracker.onTrack(tracking(0), () -> TrackResult.of("DELIVERED", null));
        tracker.onTrack(tracking(1), () -> TrackResult.of("DELIVERED", OffsetDateTime.now().plusDays(1)));

        LocalDateTime before = LocalDateTime.now();
        deliveryFacade.checkTracking(noTime, LocalDateTime.now());
        deliveryFacade.checkTracking(future, LocalDateTime.now());
        LocalDateTime after = LocalDateTime.now();

        for (long id : List.of(noTime, future)) {
            LocalDateTime deliveredAt = ((Timestamp) row(id).get("delivered_at")).toLocalDateTime();
            assertThat(deliveredAt).isBetween(before.truncatedTo(ChronoUnit.MILLIS), after.plusSeconds(1));
            assertThat(completedEvents(id)).hasSize(1);
        }
    }

    @Test
    @DisplayName("IN_TRANSIT → SHIPPED 유지, lastTrackingStatus = IN_TRANSIT, 요청 시각 null")
    void inTransit() {
        long id = shippedWithRequest(0);
        tracker.onTrack(tracking(0), () -> TrackResult.of("IN_TRANSIT", OffsetDateTime.now()));

        assertThat(deliveryFacade.checkTracking(id, LocalDateTime.now())).isEqualTo(DeliveryTaskResult.IN_PROGRESS);

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("SHIPPED");
        assertThat(row.get("last_tracking_status")).isEqualTo("IN_TRANSIT");
        assertThat(row.get("tracking_check_requested_at")).isNull();
        assertThat(completedEvents(id)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ATTEMPT_FAIL", "EXCEPTION", "AVAILABLE_FOR_PICKUP"})
    @DisplayName("완료가 아닌 마지막 상태(ATTEMPT_FAIL·EXCEPTION·AVAILABLE_FOR_PICKUP) → SHIPPED 유지 + WARN")
    void attentionStatus(String status, CapturedOutput output) {
        long id = shippedWithRequest(0);
        tracker.onTrack(tracking(0), () -> TrackResult.of(status, OffsetDateTime.now()));

        deliveryFacade.checkTracking(id, LocalDateTime.now());

        assertThat(row(id).get("status")).isEqualTo("SHIPPED");
        assertThat(row(id).get("last_tracking_status")).isEqualTo(status);
        assertThat(output).contains("배송 완료가 아닌 마지막 상태입니다: deliveryId=" + id + ", status=" + status);
    }

    @Test
    @DisplayName("NOT_FOUND → SHIPPED 유지, 요청 시각을 지운다(결정 D8: 다음 콜백·안전망이 다시 부른다)")
    void notFound() {
        long id = shippedWithRequest(0);
        tracker.onTrack(tracking(0), TrackResult::notFound);

        assertThat(deliveryFacade.checkTracking(id, LocalDateTime.now())).isEqualTo(DeliveryTaskResult.IN_PROGRESS);

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("SHIPPED");
        assertThat(row.get("tracking_check_requested_at")).isNull();
        assertThat(row.get("last_tracking_status")).isNull();
    }

    @Test
    @DisplayName("인증 실패·할당량 초과 → 예외를 그대로 던지고 요청 시각은 남는다(다음 회차에 다시)")
    void trackerFailure_keepsRequest() {
        long unauth = shippedWithRequest(0);
        long quota = shippedWithRequest(1);
        tracker.onTrack(tracking(0), () -> { throw new DeliveryTrackerUnauthenticatedException("만료"); });
        tracker.onTrack(tracking(1), () -> { throw new DeliveryTrackerQuotaExceededException("초과"); });

        assertThatThrownBy(() -> deliveryFacade.checkTracking(unauth, LocalDateTime.now()))
                .isInstanceOf(DeliveryTrackerUnauthenticatedException.class);
        assertThatThrownBy(() -> deliveryFacade.checkTracking(quota, LocalDateTime.now()))
                .isInstanceOf(DeliveryTrackerQuotaExceededException.class);

        assertThat(row(unauth).get("tracking_check_requested_at")).isNotNull();
        assertThat(row(quota).get("tracking_check_requested_at")).isNotNull();
        assertThat(row(unauth).get("status")).isEqualTo("SHIPPED");
    }

    @Test
    @DisplayName("조회 중 새 확인 요청(요청 시각 > 시작 시각) → 요청 시각을 지우지 않는다")
    void requestDuringTrack_kept() {
        long id = shippedWithRequest(0);
        LocalDateTime startedAt = LocalDateTime.now();
        LocalDateTime newRequest = startedAt.plusSeconds(1);
        tracker.onTrack(tracking(0), () -> {
            jdbcTemplate.update("update delivery set tracking_check_requested_at = ? where id = ?", newRequest, id);
            return TrackResult.of("IN_TRANSIT", OffsetDateTime.now());
        });

        deliveryFacade.checkTracking(id, startedAt);

        assertThat(((Timestamp) row(id).get("tracking_check_requested_at")).toLocalDateTime())
                .isCloseTo(newRequest, within(1, ChronoUnit.MILLIS));
    }

    @Test
    @DisplayName("조회 중 새 콜백 → 워커가 끝나도 요청 시각이 남고, 다음 회차에 DELIVERED로 완료된다 (11-08 결정 (b))")
    void callbackDuringTrack_completedNextRound() {
        long id = shippedWithRequest(0);
        LocalDateTime startedAt = LocalDateTime.now();
        // 1회차 조회 중에 배송 완료 콜백이 들어온다. 이미 요청 시각이 있어도 now로 덮어쓴다
        tracker.onTrack(tracking(0), () -> {
            sleepMillis(5);
            assertThat(deliveryFacade.requestTrackingCheck("kr.cjlogistics", tracking(0))).isEqualTo(1);
            return TrackResult.of("OUT_FOR_DELIVERY", OffsetDateTime.now());
        });

        assertThat(deliveryFacade.checkTracking(id, startedAt)).isEqualTo(DeliveryTaskResult.IN_PROGRESS);
        assertThat(row(id).get("status")).isEqualTo("SHIPPED");
        assertThat(row(id).get("tracking_check_requested_at")).isNotNull();
        assertThat(deliveryFacade.findTrackingCheckTargetIds(id - 1, 1)).containsExactly(id);

        // 2회차: 택배사 상태가 DELIVERED
        tracker.onTrack(tracking(0), () -> TrackResult.of("DELIVERED", OffsetDateTime.now().minusMinutes(1)));
        assertThat(deliveryFacade.checkTracking(id, LocalDateTime.now())).isEqualTo(DeliveryTaskResult.DONE);

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("DELIVERED");
        assertThat(row.get("tracking_check_requested_at")).isNull();
        assertThat(completedEvents(id)).hasSize(1);
    }

    @Test
    @DisplayName("track BAD_REQUEST → 예외를 던지지 않고 그 건의 요청 시각을 지우고 WARN(메시지 포함), FAILED")
    void trackBadRequest_clearsRequest(CapturedOutput output) {
        long id = shippedWithRequest(0);
        tracker.onTrack(tracking(0), () -> { throw new DeliveryTrackerBadRequestException("Delivery Tracker가 요청을 거부했습니다(BAD_REQUEST): invalid trackingNumber"); });

        assertThat(deliveryFacade.checkTracking(id, LocalDateTime.now())).isEqualTo(DeliveryTaskResult.FAILED);

        assertThat(row(id).get("tracking_check_requested_at")).isNull();
        assertThat(row(id).get("status")).isEqualTo("SHIPPED");
        assertThat(output).contains("Delivery Tracker가 조회 요청을 거부했습니다. 요청 시각을 지우고 건너뜁니다: deliveryId=" + id)
                .contains("invalid trackingNumber");
    }

    @Test
    @DisplayName("조회 중 송장 수정 → 옛 송장의 조회 결과를 버린다 (DELIVERED여도 완료 안 함)")
    void trackingChangedDuringTrack_discarded() {
        long id = shippedWithRequest(0);
        String newTracking = tracking(9);
        tracker.onTrack(tracking(0), () -> {
            deliveryFacade.changeTracking(id, Courier.CJ, newTracking);
            return TrackResult.of("DELIVERED", OffsetDateTime.now());
        });

        assertThat(deliveryFacade.checkTracking(id, LocalDateTime.now())).isEqualTo(DeliveryTaskResult.SKIPPED);

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("SHIPPED");
        assertThat(row.get("tracking_number")).isEqualTo(newTracking);
        assertThat(row.get("last_tracking_status")).isNull();
        assertThat(completedEvents(id)).isEmpty();
    }

    @Test
    @DisplayName("이미 DELIVERED인 건에 확인 요청이 다시 와도 조회하지 않고 이벤트는 1건")
    void alreadyDelivered_oneEvent() {
        long id = shippedWithRequest(0);
        tracker.onTrack(tracking(0), () -> TrackResult.of("DELIVERED", OffsetDateTime.now().minusHours(1)));
        deliveryFacade.checkTracking(id, LocalDateTime.now());

        jdbcTemplate.update("update delivery set tracking_check_requested_at = ? where id = ?", LocalDateTime.now().minusSeconds(5), id);
        assertThat(deliveryFacade.checkTracking(id, LocalDateTime.now())).isEqualTo(DeliveryTaskResult.SKIPPED);

        assertThat(completedEvents(id)).hasSize(1);
        assertThat(tracker.trackCalls()).containsExactly(tracking(0));
        assertThat(row(id).get("tracking_check_requested_at")).isNull();
    }

    @Test
    @DisplayName("확인 대상 조회: 요청이 있는 SHIPPED만 id 순으로")
    void trackingCheckTargets() {
        long requested = shippedWithRequest(0);
        long notRequested = shipped(1);

        List<Long> targets = deliveryFacade.findTrackingCheckTargetIds(requested - 1, 1000);

        assertThat(targets).contains(requested).doesNotContain(notRequested);
    }

    // ---- 웹훅 등록 ----

    @Test
    @DisplayName("미등록 건 등록 성공 → webhook_expires_at = now + 48h, 콜백 URL에 토큰, 만료 시각은 +09:00")
    void registerWebhook_success() {
        long id = shipped(0);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        assertThat(deliveryFacade.findWebhookRegisterTargetIds(id - 1, 1000)).contains(id);

        assertThat(deliveryFacade.registerWebhook(id, now)).isEqualTo(DeliveryTaskResult.DONE);

        assertThat(((Timestamp) row(id).get("webhook_expires_at")).toLocalDateTime()).isEqualTo(now.plusHours(48));
        FakeDeliveryTracker.WebhookCall call = tracker.webhookCalls().get(0);
        assertThat(call.carrierId()).isEqualTo("kr.cjlogistics");
        assertThat(call.trackingNumber()).isEqualTo(tracking(0));
        assertThat(call.callbackUrl()).isEqualTo(deliveryTrackerPolicy.callbackUrl());
        assertThat(call.expirationTime().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(call.expirationTime().toLocalDateTime()).isEqualTo(now.plusHours(48));
        assertThat(deliveryFacade.findWebhookRegisterTargetIds(id - 1, 1000)).doesNotContain(id);
    }

    @Test
    @DisplayName("등록 결과 false → webhook_expires_at null 유지 + WARN (다음 회차)")
    void registerWebhook_false(CapturedOutput output) {
        long id = shipped(0);
        tracker.onRegisterWebhook(tracking(0), () -> false);

        assertThat(deliveryFacade.registerWebhook(id, LocalDateTime.now())).isEqualTo(DeliveryTaskResult.FAILED);

        assertThat(row(id).get("webhook_expires_at")).isNull();
        assertThat(output).contains("웹훅 등록에 실패했습니다. 다음 회차에 다시 합니다: deliveryId=" + id);
    }

    @Test
    @DisplayName("웹훅 등록 BAD_REQUEST → 그 건만 SKIPPED + WARN(메시지 포함, 토큰·콜백 URL 없음), webhook_expires_at null")
    void registerWebhook_badRequest(CapturedOutput output) {
        long id = shipped(0);
        tracker.onRegisterWebhook(tracking(0), () -> {
            throw new DeliveryTrackerBadRequestException("Delivery Tracker가 요청을 거부했습니다(BAD_REQUEST): The callbackUrl argument only accepts https protocol.");
        });

        assertThat(deliveryFacade.registerWebhook(id, LocalDateTime.now())).isEqualTo(DeliveryTaskResult.SKIPPED);

        assertThat(row(id).get("webhook_expires_at")).isNull();
        assertThat(output).contains("Delivery Tracker가 웹훅 등록을 거부했습니다. 이 건은 건너뜁니다: deliveryId=" + id)
                .contains("only accepts https protocol")
                .doesNotContain(deliveryTrackerPolicy.getCallbackToken())
                .doesNotContain(deliveryTrackerPolicy.getCallbackBaseUrl());
    }

    @Test
    @DisplayName("만료 24시간 안 → 재등록 대상, 다시 등록하면 만료 연장. DELIVERED는 재등록하지 않는다")
    void renewWebhook() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        long soon = shipped(0);
        long later = shipped(1);
        long delivered = shipped(2);
        jdbcTemplate.update("update delivery set webhook_expires_at = ? where id = ?", now.plusHours(10), soon);
        jdbcTemplate.update("update delivery set webhook_expires_at = ? where id = ?", now.plusHours(40), later);
        jdbcTemplate.update("update delivery set webhook_expires_at = ?, status = 'DELIVERED' where id = ?", now.plusHours(10), delivered);

        List<Long> targets = deliveryFacade.findWebhookRenewTargetIds(now.plusHours(24), soon - 1, 1000);
        assertThat(targets).contains(soon).doesNotContain(later, delivered);

        deliveryFacade.registerWebhook(soon, now);
        assertThat(((Timestamp) row(soon).get("webhook_expires_at")).toLocalDateTime()).isEqualTo(now.plusHours(48));
        assertThat(deliveryFacade.registerWebhook(delivered, now)).isEqualTo(DeliveryTaskResult.SKIPPED);
    }

    @Test
    @DisplayName("등록 중 송장이 바뀌면 만료 시각을 기록하지 않는다(새 송장은 다음 회차에 등록)")
    void registerWebhook_trackingChanged() {
        long id = shipped(0);
        tracker.onRegisterWebhook(tracking(0), () -> {
            deliveryFacade.changeTracking(id, Courier.CJ, tracking(9));
            return true;
        });

        assertThat(deliveryFacade.registerWebhook(id, LocalDateTime.now())).isEqualTo(DeliveryTaskResult.SKIPPED);
        assertThat(row(id).get("webhook_expires_at")).isNull();
    }

    @Test
    @DisplayName("활성 웹훅 수: 만료 전인 SHIPPED만 센다")
    void countActiveWebhooks() {
        LocalDateTime now = LocalDateTime.now();
        long before = deliveryFacade.countActiveWebhooks(now);
        long active = shipped(0);
        long expired = shipped(1);
        jdbcTemplate.update("update delivery set webhook_expires_at = ? where id = ?", now.plusHours(1), active);
        jdbcTemplate.update("update delivery set webhook_expires_at = ? where id = ?", now.minusHours(1), expired);

        assertThat(deliveryFacade.countActiveWebhooks(now)).isEqualTo(before + 1);
    }

    // ---- 안전망 ----

    @Test
    @DisplayName("안전망: shipped_at이 2일보다 전인 SHIPPED만 요청 시각을 남긴다")
    void safetyCheck() {
        LocalDateTime now = LocalDateTime.now();
        long old = shipped(0);
        long recent = shipped(1);
        long oldDelivered = shipped(2);
        jdbcTemplate.update("update delivery set shipped_at = ? where id = ?", now.minusDays(3), old);
        jdbcTemplate.update("update delivery set shipped_at = ? where id = ?", now.minusDays(1), recent);
        jdbcTemplate.update("update delivery set shipped_at = ?, status = 'DELIVERED' where id = ?", now.minusDays(3), oldDelivered);

        deliveryFacade.requestSafetyCheck(now);

        assertThat(row(old).get("tracking_check_requested_at")).isNotNull();
        assertThat(row(recent).get("tracking_check_requested_at")).isNull();
        assertThat(row(oldDelivered).get("tracking_check_requested_at")).isNull();
    }

    // ---- 도움 메서드 ----

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long orderId(int n) {
        return base + n;
    }

    private String tracking(int n) {
        return base + String.format("%02d", n);
    }

    /** READY 배송을 만들고 CJ 송장을 등록해 SHIPPED로 만든다 */
    private long shipped(int n) {
        long orderId = orderId(n);
        orderIds.add(orderId);
        deliveryFacade.createFromPaidOrder(paidEvent(orderId, orderId * 10));
        long id = deliveryRepository.findByOrderId(orderId).orElseThrow().getId();
        deliveryFacade.ship(id, Courier.CJ, tracking(n));
        return id;
    }

    /** SHIPPED + 콜백을 받은 상태(요청 시각 = 5초 전) */
    private long shippedWithRequest(int n) {
        long id = shipped(n);
        jdbcTemplate.update("update delivery set tracking_check_requested_at = ? where id = ?", LocalDateTime.now().minusSeconds(5), id);
        return id;
    }

    private Map<String, Object> row(long id) {
        return jdbcTemplate.queryForMap("select * from delivery where id = ?", id);
    }

    private List<Map<String, Object>> completedEvents(long id) {
        return jdbcTemplate.queryForList("""
                select aggregate_type, payload::text as payload from outbox_event
                where event_type = 'DeliveryCompletedEvent' and aggregate_id = ?""", id);
    }
}
