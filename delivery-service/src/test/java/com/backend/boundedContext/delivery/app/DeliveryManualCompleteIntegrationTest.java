package com.backend.boundedContext.delivery.app;

import static com.backend.boundedContext.delivery.DeliveryFixtures.paidEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.backend.boundedContext.delivery.FakeDeliveryTracker;
import com.backend.boundedContext.delivery.domain.Courier;
import com.backend.boundedContext.delivery.out.DeliveryRepository;
import com.backend.boundedContext.delivery.out.TrackResult;
import com.backend.global.exception.DomainException;

/**
 * 관리자 수동 배송 완료를 실제 DB(로컬 Docker PostgreSQL, delivery 스키마)로 확인한다.
 * 확인 워커와 겹쳐도 완료·이벤트가 한 번뿐인지 가짜 Delivery Tracker로 본다.
 * 다른 테스트·로컬 데이터와 겹치지 않게 큰 주문 ID를 쓰고 끝나면 지운다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")
@Import(FakeDeliveryTracker.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class DeliveryManualCompleteIntegrationTest {

    private static final String REASON = "택배사 조회가 EXCEPTION에서 멈춰 구매자 수령을 확인함";

    @Autowired private DeliveryFacade deliveryFacade;
    @Autowired private DeliveryRepository deliveryRepository;
    @Autowired private FakeDeliveryTracker tracker;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final long base = ThreadLocalRandom.current().nextLong(5_000_000_000L, 6_000_000_000L);
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

    @Test
    @DisplayName("SHIPPED 수동 완료 → DELIVERED, 사유·처리 시각 기록, DeliveryCompletedEvent 1건(입력한 deliveredAt), 로그에 배송지·사유 없음")
    void completeManually(CapturedOutput output) {
        long id = shipped(0);
        LocalDateTime deliveredAt = LocalDateTime.now().minusMinutes(30).truncatedTo(ChronoUnit.SECONDS);

        var response = deliveryFacade.completeManually(id, deliveredAt, REASON);

        assertThat(response.status().name()).isEqualTo("DELIVERED");
        assertThat(response.manualCompletionReason()).isEqualTo(REASON);
        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("DELIVERED");
        assertThat(((Timestamp) row.get("delivered_at")).toLocalDateTime()).isEqualTo(deliveredAt);
        assertThat(row.get("manual_completion_reason")).isEqualTo(REASON);
        assertThat(row.get("manually_completed_at")).isNotNull();

        List<Map<String, Object>> events = completedEvents(id);
        assertThat(events).hasSize(1);
        assertThat((String) events.get(0).get("payload"))
                .contains("\"orderId\": " + orderId(0))
                .contains("\"buyerId\": 77")
                .contains(deliveredAt.toString().substring(0, 16));   // yyyy-MM-ddTHH:mm
        assertThat(output).contains("관리자 수동 배송 완료: deliveryId=" + id)
                .doesNotContain("테헤란로")
                .doesNotContain(REASON);
    }

    @Test
    @DisplayName("수동 완료 뒤 확인 워커가 같은 건을 처리하면 조회 없이 건너뛰고 이벤트를 더 내지 않는다")
    void completeManually_thenWorker_skipped() {
        long id = shipped(0);
        deliveryFacade.completeManually(id, LocalDateTime.now().minusMinutes(10), REASON);
        jdbcTemplate.update("update delivery set tracking_check_requested_at = ? where id = ?", LocalDateTime.now().minusSeconds(5), id);

        DeliveryTaskResult result = deliveryFacade.checkTracking(id, LocalDateTime.now());

        assertThat(result).isEqualTo(DeliveryTaskResult.SKIPPED);
        assertThat(tracker.trackCalls()).isEmpty();
        assertThat(completedEvents(id)).hasSize(1);
        assertThat(row(id).get("manual_completion_reason")).isEqualTo(REASON);
    }

    @Test
    @DisplayName("확인 워커가 조회하는 사이에 수동 완료되면, 워커는 잠근 뒤 상태를 다시 보고 건너뛴다 (이벤트 1건, 수동 완료 값 유지)")
    void manualDuringWorkerTrack_workerSkipped() {
        long id = shipped(0);
        jdbcTemplate.update("update delivery set tracking_check_requested_at = ? where id = ?", LocalDateTime.now().minusSeconds(5), id);
        LocalDateTime manualDeliveredAt = LocalDateTime.now().minusHours(2).truncatedTo(ChronoUnit.SECONDS);
        tracker.onTrack(tracking(0), () -> {
            deliveryFacade.completeManually(id, manualDeliveredAt, REASON);
            return TrackResult.of("DELIVERED", OffsetDateTime.now().minusMinutes(1));
        });

        DeliveryTaskResult result = deliveryFacade.checkTracking(id, LocalDateTime.now());

        assertThat(result).isEqualTo(DeliveryTaskResult.SKIPPED);
        assertThat(completedEvents(id)).hasSize(1);
        Map<String, Object> row = row(id);
        assertThat(((Timestamp) row.get("delivered_at")).toLocalDateTime()).isEqualTo(manualDeliveredAt);
        assertThat(row.get("manual_completion_reason")).isEqualTo(REASON);
    }

    @Test
    @DisplayName("확인 워커가 먼저 완료한 건을 수동 완료하면 409, 이벤트는 워커의 1건뿐")
    void workerCompleted_thenManual_conflict() {
        long id = shipped(0);
        jdbcTemplate.update("update delivery set tracking_check_requested_at = ? where id = ?", LocalDateTime.now().minusSeconds(5), id);
        tracker.onTrack(tracking(0), () -> TrackResult.of("DELIVERED", OffsetDateTime.now().minusHours(1)));
        deliveryFacade.checkTracking(id, LocalDateTime.now());

        assertThatThrownBy(() -> deliveryFacade.completeManually(id, LocalDateTime.now(), REASON))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-DELIVERY_STATE_INVALID");
        assertThat(completedEvents(id)).hasSize(1);
        assertThat(row(id).get("manual_completion_reason")).isNull();
    }

    @Test
    @DisplayName("READY 수동 완료는 409, 없는 배송은 404, 발송 전 시각은 400 — 이벤트 없음")
    void completeManually_errors() {
        long ready = ready(0);
        long shipped = shipped(1);

        assertThatThrownBy(() -> deliveryFacade.completeManually(ready, LocalDateTime.now(), REASON))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-DELIVERY_STATE_INVALID");
        assertThatThrownBy(() -> deliveryFacade.completeManually(Long.MAX_VALUE, LocalDateTime.now(), REASON))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-DELIVERY_NOT_FOUND");
        assertThatThrownBy(() -> deliveryFacade.completeManually(shipped, LocalDateTime.now().minusDays(4), REASON))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-DELIVERY_DELIVERED_AT_INVALID");

        assertThat(row(ready).get("status")).isEqualTo("READY");
        assertThat(row(shipped).get("status")).isEqualTo("SHIPPED");
        assertThat(completedEvents(ready)).isEmpty();
        assertThat(completedEvents(shipped)).isEmpty();
    }

    // ---- 도움 메서드 ----

    private long orderId(int n) {
        return base + n;
    }

    private String tracking(int n) {
        return base + String.format("%02d", n);
    }

    private long ready(int n) {
        long orderId = orderId(n);
        orderIds.add(orderId);
        deliveryFacade.createFromPaidOrder(paidEvent(orderId, orderId * 10));
        return deliveryRepository.findByOrderId(orderId).orElseThrow().getId();
    }

    /** READY 배송을 만들고 CJ 송장을 등록해 SHIPPED로 만든다. 발송 시각은 3일 전으로 옮긴다 */
    private long shipped(int n) {
        long id = ready(n);
        deliveryFacade.ship(id, Courier.CJ, tracking(n));
        jdbcTemplate.update("update delivery set shipped_at = ? where id = ?", LocalDateTime.now().minusDays(3), id);
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
