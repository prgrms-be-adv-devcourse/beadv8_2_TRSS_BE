package com.backend.boundedContext.order.in.dto;

import com.backend.shared.order.dto.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 주문 상세
 *
 * @param remainingSeconds 결제 가능 남은 시간(초)
 * @param payments            결제 내역 (결제 시도가 없으면 빈 목록)
 * @param shippingAddress     배송지. 배송지 없이 만든 주문이면 null
 * @param deliveredAt         배송 완료 시각(market이 받은 배송 완료 이벤트 기준). 배송 전이면 null
 * @param delivery            배송 정보. 배송지 없는 주문·결제 전·배송 건이 아직 없음(결제 직후)·조회 실패면 null
 * @param deliveryUnavailable 배송 정보를 조회하지 못했으면 true (delivery 서비스 장애). 이때도 주문 상세는 200으로 응답한다
 */
public record OrderDetailResponse(
        Long orderId,
        String orderNumber,
        OrderStatus status,
        long totalPrice,
        LocalDateTime orderedAt,
        LocalDateTime expiresAt,
        long remainingSeconds,
        LocalDateTime paidAt,
        LocalDateTime canceledAt,
        LocalDateTime confirmedAt,
        List<OrderItemResponse> items,
        List<OrderPaymentResponse> payments,
        ShippingAddressResponse shippingAddress,
        LocalDateTime deliveredAt,
        OrderDeliveryResponse delivery,
        boolean deliveryUnavailable
) {
}