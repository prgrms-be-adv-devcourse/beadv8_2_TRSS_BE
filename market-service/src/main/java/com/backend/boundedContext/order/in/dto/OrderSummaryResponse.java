package com.backend.boundedContext.order.in.dto;

import com.backend.shared.order.dto.OrderStatus;

import java.time.LocalDateTime;

/**
 * 주문 목록의 한 줄
 *
 * @param totalPrice                결제 금액(쿠폰 할인 후, 배송비 포함). payAmount와 같은 값이다. 기존 화면 호환용으로 남기며 새 화면은 payAmount를 쓴다
 * @param representativeProductName 대표 상품명
 * @param thumbnailUrl              대표 상품의 현재 대표 이미지 URL. 상품이 삭제됐거나 이미지가 없으면 null
 * @param itemCount                 주문 항목 수
 * @param orderedAt                 주문 일시
 * @param expiresAt                 결제 만료 시각
 * @param remainingSeconds          결제 가능 남은 시간(초)
 * @param paidAt                    결제 일시(결제 전이면 null)
 * @param deliveredAt               배송 완료 일시(배송 전·배송지 없는 주문이면 null)
 * @param payAmount                 결제 금액 = 상품 금액 합계 - 상품 쿠폰 할인 - 장바구니 쿠폰 할인 + 배송비. 상세의 payAmount와 같다
 */
public record OrderSummaryResponse(
        Long orderId,
        String orderNumber,
        OrderStatus status,
        long totalPrice,
        String representativeProductName,
        String thumbnailUrl,
        int itemCount,
        LocalDateTime orderedAt,
        LocalDateTime expiresAt,
        long remainingSeconds,
        LocalDateTime paidAt,
        LocalDateTime deliveredAt,
        long payAmount
) {
}
