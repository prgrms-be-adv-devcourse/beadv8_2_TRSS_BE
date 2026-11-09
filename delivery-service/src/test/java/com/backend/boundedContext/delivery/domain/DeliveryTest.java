package com.backend.boundedContext.delivery.domain;

import static com.backend.boundedContext.delivery.DeliveryFixtures.address;
import static com.backend.boundedContext.delivery.DeliveryFixtures.item;
import static com.backend.boundedContext.delivery.DeliveryFixtures.paidEvent;
import static com.backend.boundedContext.delivery.DeliveryFixtures.withAddress;
import static com.backend.boundedContext.delivery.DeliveryFixtures.withItems;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.global.exception.DomainException;
import com.backend.shared.delivery.dto.DeliveryDto;
import com.backend.shared.delivery.dto.DeliveryStatus;
import com.backend.shared.order.dto.PaidOrderItemDto;
import com.backend.shared.order.dto.ShippingAddressDto;
import com.backend.shared.order.event.OrderPaidEvent;

class DeliveryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 7, 9, 0);

    @Test
    @DisplayName("create: READY로 만들고 배송지와 모든 항목(판매자 여러 명)을 복사한다")
    void create() {
        OrderPaidEvent event = paidEvent(10L, 100L);

        Delivery delivery = Delivery.create(event);

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.READY);
        assertThat(delivery.getOrderId()).isEqualTo(10L);
        assertThat(delivery.getOrderNumber()).isEqualTo("ORD-10");
        assertThat(delivery.getBuyerId()).isEqualTo(77L);
        assertThat(delivery.getPaidAt()).isEqualTo(event.paidAt());
        ShippingAddressDto address = address();
        assertThat(delivery.getReceiverName()).isEqualTo(address.receiverName());
        assertThat(delivery.getReceiverPhone()).isEqualTo(address.receiverPhone());
        assertThat(delivery.getZipCode()).isEqualTo(address.zipCode());
        assertThat(delivery.getAddress()).isEqualTo(address.address());
        assertThat(delivery.getAddressDetail()).isEqualTo(address.addressDetail());
        assertThat(delivery.getRequestMessage()).isEqualTo(address.requestMessage());
        assertThat(delivery.getItems()).hasSize(3);
        assertThat(delivery.getItems()).extracting(DeliveryItem::getSellerId).containsExactly(1L, 1L, 2L);
        DeliveryItem first = delivery.getItems().getFirst();
        assertThat(first.getDelivery()).isSameAs(delivery);
        assertThat(first.getOrderItemId()).isEqualTo(100L);
        assertThat(first.getProductId()).isEqualTo(1100L);
        assertThat(first.getOptionId()).isEqualTo(2100L);
        assertThat(first.getSellerName()).isEqualTo("판매자1");
        assertThat(first.getProductName()).isEqualTo("상품100");
        assertThat(first.getOptionName()).isEqualTo("옵션100");
        assertThat(first.getQuantity()).isEqualTo(1);
        assertThat(delivery.getCourier()).isNull();
        assertThat(delivery.getTrackingNumber()).isNull();
    }

    @Test
    @DisplayName("create: 배송지가 없으면 IllegalArgumentException (재시도 없이 DLT)")
    void create_noAddress() {
        assertThatThrownBy(() -> Delivery.create(withAddress(paidEvent(10L, 100L), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shippingAddress");
    }

    @Test
    @DisplayName("create: 배송지 필수 값(수령인 등)이 없으면 IllegalArgumentException")
    void create_addressFieldMissing() {
        ShippingAddressDto noReceiver = new ShippingAddressDto(null, "010", "06236", "서울", null, null);

        assertThatThrownBy(() -> Delivery.create(withAddress(paidEvent(10L, 100L), noReceiver)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("create: 항목이 빈 목록이거나 null이면 IllegalArgumentException")
    void create_noItems() {
        assertThatThrownBy(() -> Delivery.create(withItems(paidEvent(10L, 100L), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("items");
        assertThatThrownBy(() -> Delivery.create(withItems(paidEvent(10L, 100L), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("items");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("create: 수량이 0 이하인 항목이 하나라도 있으면 IllegalArgumentException")
    void create_quantityNotPositive(int quantity) {
        List<PaidOrderItemDto> items = List.of(item(100L, 1L, 1), item(101L, 2L, quantity));

        assertThatThrownBy(() -> Delivery.create(withItems(paidEvent(10L, 100L), items)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity");
    }

    @Test
    @DisplayName("create: 항목 필수 값(optionName 등)이 없으면 IllegalArgumentException")
    void create_itemFieldMissing() {
        List<PaidOrderItemDto> items = new ArrayList<>();
        items.add(new PaidOrderItemDto(100L, 1L, 2L, 3L, "판매자", "상품", null, 1));

        assertThatThrownBy(() -> Delivery.create(withItems(paidEvent(10L, 100L), items)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("create 예외 메시지에 배송지 값이 들어가지 않는다")
    void create_messageHasNoAddress() {
        ShippingAddressDto noZip = new ShippingAddressDto("홍길동", "010-1234-5678", null, "서울시 강남구", null, null);

        assertThatThrownBy(() -> Delivery.create(withAddress(paidEvent(10L, 100L), noZip)))
                .hasMessageNotContaining("홍길동")
                .hasMessageNotContaining("010-1234-5678")
                .hasMessageNotContaining("서울시 강남구");
    }

    @Test
    @DisplayName("ship: READY → SHIPPED, 택배사·송장번호·출고 시각을 남기고 웹훅은 비워 둔다")
    void ship() {
        Delivery delivery = Delivery.create(paidEvent(10L, 100L));

        delivery.ship(Courier.CJ, "123456789012", NOW);

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SHIPPED);
        assertThat(delivery.getCourier()).isEqualTo(Courier.CJ);
        assertThat(delivery.getTrackingNumber()).isEqualTo("123456789012");
        assertThat(delivery.getShippedAt()).isEqualTo(NOW);
        assertThat(delivery.getWebhookExpiresAt()).isNull();
        assertThat(delivery.getTrackingCheckRequestedAt()).isNull();
    }

    @Test
    @DisplayName("ship: SHIPPED·DELIVERED에서는 DELIVERY_STATE_INVALID")
    void ship_notReady() {
        Delivery shipped = Delivery.create(paidEvent(10L, 100L));
        shipped.ship(Courier.CJ, "123456789012", NOW);
        Delivery delivered = Delivery.create(paidEvent(11L, 200L));
        ReflectionTestUtils.setField(delivered, "status", DeliveryStatus.DELIVERED);

        assertStateInvalid(() -> shipped.ship(Courier.POST, "999999999999", NOW));
        assertStateInvalid(() -> delivered.ship(Courier.POST, "999999999999", NOW));
        assertThat(shipped.getTrackingNumber()).isEqualTo("123456789012");
    }

    @Test
    @DisplayName("changeTracking: SHIPPED에서 바꾸면 웹훅·조회 요청·마지막 추적 상태를 비운다")
    void changeTracking() {
        Delivery delivery = Delivery.create(paidEvent(10L, 100L));
        delivery.ship(Courier.CJ, "123456789012", NOW);
        ReflectionTestUtils.setField(delivery, "webhookExpiresAt", NOW.plusDays(2));
        ReflectionTestUtils.setField(delivery, "trackingCheckRequestedAt", NOW);
        ReflectionTestUtils.setField(delivery, "lastTrackingStatus", "IN_TRANSIT");

        boolean changed = delivery.changeTracking(Courier.POST, "6896123456789");

        assertThat(changed).isTrue();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SHIPPED);
        assertThat(delivery.getCourier()).isEqualTo(Courier.POST);
        assertThat(delivery.getTrackingNumber()).isEqualTo("6896123456789");
        assertThat(delivery.getShippedAt()).isEqualTo(NOW);
        assertThat(delivery.getWebhookExpiresAt()).isNull();
        assertThat(delivery.getTrackingCheckRequestedAt()).isNull();
        assertThat(delivery.getLastTrackingStatus()).isNull();
    }

    @Test
    @DisplayName("changeTracking: 같은 값이면 아무것도 바꾸지 않는다")
    void changeTracking_sameValue() {
        Delivery delivery = Delivery.create(paidEvent(10L, 100L));
        delivery.ship(Courier.CJ, "123456789012", NOW);
        LocalDateTime expiresAt = NOW.plusDays(2);
        ReflectionTestUtils.setField(delivery, "webhookExpiresAt", expiresAt);
        ReflectionTestUtils.setField(delivery, "lastTrackingStatus", "IN_TRANSIT");

        boolean changed = delivery.changeTracking(Courier.CJ, "123456789012");

        assertThat(changed).isFalse();
        assertThat(delivery.getWebhookExpiresAt()).isEqualTo(expiresAt);
        assertThat(delivery.getLastTrackingStatus()).isEqualTo("IN_TRANSIT");
    }

    @Test
    @DisplayName("changeTracking: READY에서는 DELIVERY_STATE_INVALID")
    void changeTracking_ready() {
        Delivery delivery = Delivery.create(paidEvent(10L, 100L));

        assertStateInvalid(() -> delivery.changeTracking(Courier.CJ, "123456789012"));
    }

    @Test
    @DisplayName("toDto: READY면 택배사·송장·출고 시각이 null, SHIPPED면 택배사 표시 이름을 담는다")
    void toDto() {
        Delivery delivery = Delivery.create(paidEvent(10L, 100L));
        ReflectionTestUtils.setField(delivery, "id", 5L);

        DeliveryDto ready = delivery.toDto();
        delivery.ship(Courier.POST, "6896123456789", NOW);
        DeliveryDto shipped = delivery.toDto();

        assertThat(ready).isEqualTo(new DeliveryDto(5L, DeliveryStatus.READY, null, null, null, null));
        assertThat(shipped).isEqualTo(new DeliveryDto(5L, DeliveryStatus.SHIPPED, "우체국택배", "6896123456789", NOW, null));
    }

    private static void assertStateInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-DELIVERY_STATE_INVALID");
    }
}
