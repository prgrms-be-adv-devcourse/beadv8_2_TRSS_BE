package com.backend.boundedContext.order.in.dto;

import com.backend.boundedContext.order.domain.ReturnReasonCode;
import com.backend.shared.order.dto.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 주문 상세
 *
 * @param remainingSeconds 결제 가능 남은 시간(초)
 * @param payments            결제 내역 (결제 시도가 없거나 조회하지 못했으면 빈 목록)
 * @param paymentUnavailable  결제 내역을 조회하지 못했으면 true (payment 서비스 장애). 이때도 주문 상세는 200으로 응답한다
 * @param shippingAddress     배송지. 배송지 없이 만든 주문이면 null
 * @param deliveredAt         배송 완료 시각(market이 받은 배송 완료 이벤트 기준). 배송 전이면 null
 * @param delivery            배송 정보. 배송지 없는 주문·결제 전·배송 건이 아직 없음(결제 직후)·조회 실패면 null
 * @param deliveryUnavailable 배송 정보를 조회하지 못했으면 true (delivery 서비스 장애). 이때도 주문 상세는 200으로 응답한다
 * @param totalPrice          상품 금액 합계 (할인 전)
 * @param productDiscountAmount    상품 쿠폰 할인 합계
 * @param cartCouponDiscountAmount 장바구니 쿠폰 할인액
 * @param payAmount                결제 금액 = 상품 금액 합계 - 상품 쿠폰 할인 - 장바구니 쿠폰 할인 + 배송비
 * @param shippingFee              배송비. 상품 쿠폰 할인 후 상품 금액 합계가 30,000원 이상이면 0원, 미만이면 2,500원 (배송비 도입 전 주문은 0)
 * @param returnRequestedAt  반품 요청 시각. 요청하지 않았으면 null (거절된 뒤에도 값이 남아 다시 요청할 수 없다)
 * @param returnReasonCode   반품 사유 코드. 요청하지 않았으면 null
 * @param returnReason       반품 상세 사유. 적지 않았으면 null
 * @param returnRejectedAt   반품 거절 시각. 거절되지 않았으면 null
 * @param returnRejectReason 반품 거절 사유
 * @param returnedAt         반품 승인(RETURNED) 시각
 * @param refundStatus       환불 상태. RETURNED가 아니면 NONE, RETURNED이고 결제 시도에 REFUNDED가 있으면 COMPLETED, 없으면 PENDING,
 *                           결제 내역을 조회하지 못했으면 UNKNOWN
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
        boolean deliveryUnavailable,
        long productDiscountAmount,
        long cartCouponDiscountAmount,
        long payAmount,
        long shippingFee,
        boolean paymentUnavailable,
        LocalDateTime returnRequestedAt,
        ReturnReasonCode returnReasonCode,
        String returnReason,
        LocalDateTime returnRejectedAt,
        String returnRejectReason,
        LocalDateTime returnedAt,
        RefundStatus refundStatus
) {
}