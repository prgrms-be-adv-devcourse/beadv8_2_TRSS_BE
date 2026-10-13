package com.backend.boundedContext.payment.out.toss;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 토스 Payment 객체 중 충전에 필요한 필드만 받음
 * status: READY, IN_PROGRESS, WAITING_FOR_DEPOSIT, DONE, CANCELED, PARTIAL_CANCELED, ABORTED, EXPIRED
 * method: 카드, 가상계좌, 간편결제, 휴대폰, 계좌이체 등 (한글 값)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossPaymentResponse(
        String paymentKey,
        String orderId,
        String status,
        long totalAmount,
        String method,
        OffsetDateTime approvedAt
) {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 토스에서 결제 승인이 완료됐는지 (토스 status가 DONE). 우리 충전 상태(TopupStatus)와는 별개
    public boolean isApproved() {
        return "DONE".equals(status);
    }

    // 토스는 시각을 오프셋 포함 문자열로 주므로 KST LocalDateTime으로 바꿔 쓴다
    public LocalDateTime approvedAtKst() {
        return approvedAt == null ? null : approvedAt.atZoneSameInstant(KST).toLocalDateTime();
    }
}
