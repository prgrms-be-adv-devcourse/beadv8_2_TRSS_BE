package com.backend.boundedContext.delivery.app;

import static com.backend.boundedContext.delivery.DeliveryFixtures.item;
import static com.backend.boundedContext.delivery.DeliveryFixtures.paidEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.backend.boundedContext.delivery.domain.Courier;
import com.backend.boundedContext.delivery.in.dto.LogisticsDeliveryResponse;
import com.backend.boundedContext.delivery.out.DeliveryRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.page.PageResponse;
import com.backend.shared.delivery.dto.DeliveryDto;
import com.backend.shared.delivery.dto.DeliveryStatus;
import com.backend.shared.delivery.out.DeliveryApi;

/**
 * 배송 생성·송장 등록·수정·조회를 실제 DB(로컬 Docker PostgreSQL, delivery 스키마)로 확인한다.
 * 다른 테스트·로컬 데이터와 겹치지 않게 큰 주문 ID를 쓰고 끝나면 지운다.
 */
@SpringBootTest(properties = "custom.scheduling.enabled=false")
class DeliveryFacadeIntegrationTest {

    @Autowired private DeliveryFacade deliveryFacade;
    @Autowired private DeliveryApi deliveryApi;
    @Autowired private DeliveryRepository deliveryRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    // 다른 테스트·로컬 데이터와 겹치지 않는 주문·주문 항목 ID 범위
    private final long base = ThreadLocalRandom.current().nextLong(7_000_000_000L, 8_000_000_000L);
    private final List<Long> orderIds = new ArrayList<>();

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
    @DisplayName("판매자 2명·항목 3개 결제 → 배송 1건(READY), 항목 3개, 배송지 저장")
    void create_multiSeller() {
        long orderId = create(0, LocalDateTime.of(2026, 11, 6, 10, 0));

        LogisticsDeliveryResponse delivery = getByOrder(orderId);
        assertThat(delivery.status()).isEqualTo(DeliveryStatus.READY);
        assertThat(delivery.orderNumber()).isEqualTo("ORD-" + orderId);
        assertThat(delivery.receiverName()).isEqualTo("홍길동");
        assertThat(delivery.zipCode()).isEqualTo("06236");
        assertThat(delivery.items()).hasSize(3);
        assertThat(delivery.items()).extracting(LogisticsDeliveryResponse.Item::sellerId).containsExactly(1L, 1L, 2L);
        assertThat(delivery.items()).extracting(LogisticsDeliveryResponse.Item::quantity).containsExactly(1, 2, 3);
        assertThat(delivery.courier()).isNull();
        assertThat(delivery.carrierName()).isNull();
    }

    @Test
    @DisplayName("같은 주문의 결제 이벤트가 다시 와도(eventId 다름) 배송은 1건")
    void create_sameOrderTwice() {
        long orderId = create(0, LocalDateTime.now());

        deliveryFacade.createFromPaidOrder(paidEvent(orderId, base * 10 + 500));

        assertThat(jdbcTemplate.queryForObject("select count(*) from delivery where order_id = ?", Integer.class, orderId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("송장 등록: 하이픈·공백을 지워 저장하고 SHIPPED, 웹훅 미등록(null)")
    void ship_normalized() {
        long deliveryId = deliveryIdOf(create(0, LocalDateTime.now()));

        LogisticsDeliveryResponse shipped = deliveryFacade.ship(deliveryId, Courier.CJ, " " + tracking(1, "-") + " ");

        assertThat(shipped.status()).isEqualTo(DeliveryStatus.SHIPPED);
        assertThat(shipped.courier()).isEqualTo(Courier.CJ);
        assertThat(shipped.carrierName()).isEqualTo("CJ대한통운");
        assertThat(shipped.trackingNumber()).isEqualTo(tracking(1, ""));
        assertThat(shipped.shippedAt()).isNotNull();
        assertThat(deliveryRepository.findById(deliveryId).orElseThrow().getWebhookExpiresAt()).isNull();
    }

    @Test
    @DisplayName("송장 등록: 없는 배송 404, 이미 SHIPPED면 409 DELIVERY_STATE_INVALID")
    void ship_notFoundAndNotReady() {
        long deliveryId = deliveryIdOf(create(0, LocalDateTime.now()));
        deliveryFacade.ship(deliveryId, Courier.CJ, tracking(1, ""));

        assertError(() -> deliveryFacade.ship(-1L, Courier.CJ, tracking(2, "")), "404-DELIVERY_NOT_FOUND");
        assertError(() -> deliveryFacade.ship(deliveryId, Courier.CJ, tracking(2, "")), "409-DELIVERY_STATE_INVALID");
    }

    @Test
    @DisplayName("송장 등록: 다른 배송 건과 같은 택배사·송장(정규화 후 같음)이면 409, 택배사가 다르면 등록된다")
    void ship_duplicated() {
        long first = deliveryIdOf(create(0, LocalDateTime.now()));
        long second = deliveryIdOf(create(1, LocalDateTime.now()));
        deliveryFacade.ship(first, Courier.CJ, tracking(1, ""));

        assertError(() -> deliveryFacade.ship(second, Courier.CJ, tracking(1, "-")), "409-DELIVERY_TRACKING_DUPLICATED");
        assertThat(deliveryFacade.ship(second, Courier.POST, tracking(1, "")).status()).isEqualTo(DeliveryStatus.SHIPPED);
    }

    @Test
    @DisplayName("송장 등록: 정규화 뒤 7자 이하면 400 DELIVERY_TRACKING_NUMBER_INVALID, 상태는 그대로")
    void ship_tooShort() {
        long deliveryId = deliveryIdOf(create(0, LocalDateTime.now()));

        assertError(() -> deliveryFacade.ship(deliveryId, Courier.CJ, "123-4567"), "400-DELIVERY_TRACKING_NUMBER_INVALID");
        assertThat(deliveryFacade.getDelivery(deliveryId).status()).isEqualTo(DeliveryStatus.READY);
    }

    @Test
    @DisplayName("송장 수정: READY면 409, SHIPPED면 바뀌고, 같은 값이면 200·변화 없음, 다른 건 송장이면 409")
    void changeTracking() {
        long deliveryId = deliveryIdOf(create(0, LocalDateTime.now()));
        long other = deliveryIdOf(create(1, LocalDateTime.now()));

        assertError(() -> deliveryFacade.changeTracking(deliveryId, Courier.CJ, tracking(1, "")), "409-DELIVERY_STATE_INVALID");

        deliveryFacade.ship(deliveryId, Courier.CJ, tracking(1, ""));
        deliveryFacade.ship(other, Courier.POST, tracking(9, ""));

        LogisticsDeliveryResponse changed = deliveryFacade.changeTracking(deliveryId, Courier.POST, tracking(2, "-"));
        assertThat(changed.courier()).isEqualTo(Courier.POST);
        assertThat(changed.trackingNumber()).isEqualTo(tracking(2, ""));

        LogisticsDeliveryResponse same = deliveryFacade.changeTracking(deliveryId, Courier.POST, tracking(2, ""));
        assertThat(same.trackingNumber()).isEqualTo(tracking(2, ""));
        assertThat(same.status()).isEqualTo(DeliveryStatus.SHIPPED);

        assertError(() -> deliveryFacade.changeTracking(deliveryId, Courier.POST, tracking(9, "")), "409-DELIVERY_TRACKING_DUPLICATED");
    }

    @Test
    @DisplayName("송장 등록 → outbox에 DeliveryShippedEvent 1건(DELIVERY/배송 ID), 알림용 값이 실리고 배송지·연락처는 없다")
    void ship_publishesShippedEvent() {
        long orderId = create(0, LocalDateTime.now());
        long deliveryId = deliveryIdOf(orderId);

        LogisticsDeliveryResponse shipped = deliveryFacade.ship(deliveryId, Courier.CJ, tracking(1, "-"));

        List<Map<String, Object>> events = shippedEvents(deliveryId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("aggregate_type")).isEqualTo("DELIVERY");
        assertThat(events.get(0).get("status")).isEqualTo("PENDING");
        String payload = (String) events.get(0).get("payload");
        assertThat(payload)
                .contains("\"deliveryId\": " + deliveryId)
                .contains("\"orderId\": " + orderId)
                .contains("\"buyerId\": 77")
                .contains("\"carrierName\": \"CJ대한통운\"")
                .contains("\"trackingNumber\": \"" + tracking(1, "") + "\"")
                .contains("\"shippedAt\": \"" + shipped.shippedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")));
        assertThat(payload).doesNotContain("홍길동", "010-1234-5678", "테헤란로", "receiver", "address");
    }

    @Test
    @DisplayName("송장 등록이 실패하면(READY 아님·송장 중복·송장 형식) 배송 시작 이벤트가 더 쌓이지 않는다")
    void ship_failure_noEvent() {
        long first = deliveryIdOf(create(0, LocalDateTime.now()));
        long second = deliveryIdOf(create(1, LocalDateTime.now()));
        deliveryFacade.ship(first, Courier.CJ, tracking(1, ""));

        assertError(() -> deliveryFacade.ship(first, Courier.POST, tracking(2, "")), "409-DELIVERY_STATE_INVALID");
        assertError(() -> deliveryFacade.ship(second, Courier.CJ, tracking(1, "")), "409-DELIVERY_TRACKING_DUPLICATED");
        assertError(() -> deliveryFacade.ship(second, Courier.CJ, "123-4567"), "400-DELIVERY_TRACKING_NUMBER_INVALID");

        assertThat(shippedEvents(first)).hasSize(1);
        assertThat(shippedEvents(second)).isEmpty();
    }

    @Test
    @DisplayName("송장 수정은 배송 시작 이벤트를 다시 내지 않는다 (등록 때 1건 그대로, 처음 송장 값 유지)")
    void changeTracking_noEvent() {
        long deliveryId = deliveryIdOf(create(0, LocalDateTime.now()));
        deliveryFacade.ship(deliveryId, Courier.CJ, tracking(1, ""));

        deliveryFacade.changeTracking(deliveryId, Courier.POST, tracking(2, ""));

        List<Map<String, Object>> events = shippedEvents(deliveryId);
        assertThat(events).hasSize(1);
        assertThat((String) events.get(0).get("payload")).contains(tracking(1, "")).doesNotContain(tracking(2, ""));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from outbox_event where aggregate_type = 'DELIVERY' and aggregate_id = ?", Integer.class, deliveryId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("목록: status로 거르고, READY는 결제가 오래된 순, SHIPPED는 최신순. 항목을 포함한다")
    void getDeliveries() {
        LocalDateTime old = LocalDateTime.of(2000, 1, 1, 0, 0);   // 로컬 데이터보다 먼저 오게 아주 오래된 결제
        long newer = create(0, old.plusHours(2));
        long older = create(1, old);
        long shipped = create(2, old.plusHours(1));
        deliveryFacade.ship(deliveryIdOf(shipped), Courier.CJ, tracking(3, ""));

        PageResponse<LogisticsDeliveryResponse> ready = deliveryFacade.getDeliveries(DeliveryStatus.READY, PageRequest.of(0, 100));
        List<Long> readyOrderIds = ready.items().stream().map(LogisticsDeliveryResponse::orderId).toList();
        assertThat(readyOrderIds).startsWith(older, newer).doesNotContain(shipped);
        assertThat(ready.items().getFirst().items()).hasSize(3);
        assertThat(ready.items()).allMatch(d -> d.status() == DeliveryStatus.READY);

        PageResponse<LogisticsDeliveryResponse> shippedPage = deliveryFacade.getDeliveries(DeliveryStatus.SHIPPED, PageRequest.of(0, 100));
        assertThat(shippedPage.items()).allMatch(d -> d.status() == DeliveryStatus.SHIPPED);
        assertThat(shippedPage.items()).extracting(LogisticsDeliveryResponse::orderId).contains(shipped).doesNotContain(older, newer);
        List<Long> shippedIds = shippedPage.items().stream().map(LogisticsDeliveryResponse::deliveryId).toList();
        assertThat(shippedIds).isSortedAccordingTo((a, b) -> Long.compare(b, a));
    }

    @Test
    @DisplayName("DeliveryApi: 배송이 있으면 DeliveryDto, 없으면 empty")
    void deliveryApi() {
        long orderId = create(0, LocalDateTime.now());
        long deliveryId = deliveryIdOf(orderId);
        deliveryFacade.ship(deliveryId, Courier.POST, tracking(1, ""));

        DeliveryDto dto = deliveryApi.findByOrderId(orderId).orElseThrow();

        assertThat(dto.deliveryId()).isEqualTo(deliveryId);
        assertThat(dto.status()).isEqualTo(DeliveryStatus.SHIPPED);
        assertThat(dto.carrierName()).isEqualTo("우체국택배");
        assertThat(dto.trackingNumber()).isEqualTo(tracking(1, ""));
        assertThat(dto.deliveredAt()).isNull();
        assertThat(deliveryApi.findByOrderId(-1L)).isEmpty();
    }

    @Test
    @DisplayName("수량 0 항목이 있는 이벤트는 IllegalArgumentException이고 아무것도 저장하지 않는다")
    void create_quantityZero() {
        long orderId = base + 50;
        orderIds.add(orderId);

        assertThatThrownBy(() -> deliveryFacade.createFromPaidOrder(paidEvent(orderId, LocalDateTime.now(),
                List.of(item(base * 10 + 900, 1L, 0)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(deliveryRepository.existsByOrderId(orderId)).isFalse();
    }

    // n번째 주문의 결제 이벤트로 배송을 만들고 주문 ID를 돌려준다
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

    private List<Map<String, Object>> shippedEvents(long deliveryId) {
        return jdbcTemplate.queryForList("""
                select aggregate_type, status, payload::text as payload from outbox_event
                where event_type = 'DeliveryShippedEvent' and aggregate_id = ?""", deliveryId);
    }

    private LogisticsDeliveryResponse getByOrder(long orderId) {
        return deliveryFacade.getDelivery(deliveryIdOf(orderId));
    }

    // 이 테스트 전용 송장번호 (base로 다른 실행과 겹치지 않게). separator로 하이픈을 끼운 입력을 만든다
    private String tracking(int n, String separator) {
        String digits = String.valueOf(base) + n;   // 11자
        return digits.substring(0, 4) + separator + digits.substring(4, 8) + separator + digits.substring(8);
    }

    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String resultCode) {
        assertThatThrownBy(call)
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo(resultCode);
    }
}
