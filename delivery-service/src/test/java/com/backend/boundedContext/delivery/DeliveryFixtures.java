package com.backend.boundedContext.delivery;

import java.time.LocalDateTime;
import java.util.List;

import com.backend.shared.order.dto.PaidOrderItemDto;
import com.backend.shared.order.dto.ShippingAddressDto;
import com.backend.shared.order.event.OrderPaidEvent;

/**
 * 배송 테스트용 결제 완료 이벤트
 */
public final class DeliveryFixtures {

    public static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 11, 6, 10, 0);

    private DeliveryFixtures() {
    }

    public static ShippingAddressDto address() {
        return new ShippingAddressDto("홍길동", "010-1234-5678", "06236", "서울시 강남구 테헤란로 1", "101동 202호", "문 앞에 놔주세요");
    }

    public static PaidOrderItemDto item(long orderItemId, long sellerId, int quantity) {
        return new PaidOrderItemDto(orderItemId, orderItemId + 1000, orderItemId + 2000, sellerId,
                "판매자" + sellerId, "상품" + orderItemId, "옵션" + orderItemId, quantity);
    }

    /** 판매자 2명, 항목 3개 */
    public static OrderPaidEvent paidEvent(long orderId, long orderItemIdBase) {
        return paidEvent(orderId, PAID_AT, List.of(
                item(orderItemIdBase, 1L, 1),
                item(orderItemIdBase + 1, 1L, 2),
                item(orderItemIdBase + 2, 2L, 3)));
    }

    public static OrderPaidEvent paidEvent(long orderId, LocalDateTime paidAt, List<PaidOrderItemDto> items) {
        return new OrderPaidEvent(orderId, "ORD-" + orderId, 77L, paidAt, address(), items);
    }

    public static OrderPaidEvent withAddress(OrderPaidEvent event, ShippingAddressDto address) {
        return new OrderPaidEvent(event.orderId(), event.orderNumber(), event.buyerId(), event.paidAt(), address, event.items());
    }

    public static OrderPaidEvent withItems(OrderPaidEvent event, List<PaidOrderItemDto> items) {
        return new OrderPaidEvent(event.orderId(), event.orderNumber(), event.buyerId(), event.paidAt(),
                event.shippingAddress(), items);
    }
}
