package com.backend.shared.payment.dto;

import java.time.LocalDateTime;

// 주문 모듈의 결제 요청
public record PayCommand(
        Long orderId,
        String orderNumber,  // 결제 건에 함께 저장해 관리자 결제 조회에서 보여 주고 검색 (주문번호는 바뀌지 않음)
        Long buyerId,
        long amount,         // 구매자 결제 금액 (배송비 포함, 쿠폰 할인 후)
        long shippingFee,    // amount 중 배송비 (0 이상, amount 미만). 홀딩에 넣지 않고 플랫폼 지갑으로 옮긴다 (SHIPPING_FEE)
        long couponSubsidyAmount, // 장바구니 쿠폰 지원금 = 주문의 장바구니 쿠폰 할인액 (0 이상). 플랫폼 부담이라 플랫폼 지갑 → 홀딩 (COUPON_SUBSIDY)
        LocalDateTime orderExpiresAt,
        String idempotencyKey
) {
    // 배송비·지원금 필드 추가 전 호출부용 (둘 다 0)
    public PayCommand(Long orderId, String orderNumber, Long buyerId, long amount,
                      LocalDateTime orderExpiresAt, String idempotencyKey) {
        this(orderId, orderNumber, buyerId, amount, 0L, 0L, orderExpiresAt, idempotencyKey);
    }

    public boolean isExpiredAt(LocalDateTime now) {
        return !now.isBefore(orderExpiresAt);
    }
}
