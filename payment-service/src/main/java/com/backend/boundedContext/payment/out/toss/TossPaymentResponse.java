package com.backend.boundedContext.payment.out.toss;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 토스 Payment 객체 중 충전에 필요한 필드만 받음
 * status: READY, IN_PROGRESS, WAITING_FOR_DEPOSIT, DONE, CANCELED, PARTIAL_CANCELED, ABORTED, EXPIRED
 * method: 카드, 가상계좌, 간편결제, 휴대폰, 계좌이체 등 (한글 값)
 * cancels: 취소 내역. 부분 취소할 때마다 한 건씩 쌓이며, 취소된 적 없으면 null
 * 충전 로직은 이 응답을 직접 쓰지 않고, TossTopupGatewayAdapter가 PgPaymentResult로 바꿔 넘김
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossPaymentResponse(
        String paymentKey,
        String orderId,
        String status,
        long totalAmount,
        String method,
        OffsetDateTime approvedAt,
        List<Cancel> cancels
) {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 취소 내역 중 충전에 필요한 필드만 받음. cancelStatus가 DONE이면 취소 성공
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cancel(
            long cancelAmount,
            String cancelStatus
    ) {}

    // 취소에 성공한 금액 합계
    // 토스 문서상 cancelStatus는 DONE만 성공. 필드가 없으면 cancels에 기록된 취소로 보고 포함함 (실제 취소를 0으로 읽으면 대사가 잔액을 되돌려 이중 환불이 됨)
    public long canceledAmount() {
        if (cancels == null) return 0;
        return cancels.stream()
                .filter(cancel -> cancel.cancelStatus() == null || "DONE".equals(cancel.cancelStatus()))
                .mapToLong(Cancel::cancelAmount)
                .sum();
    }

    // 토스는 시각을 오프셋 포함 문자열로 주므로 KST LocalDateTime으로 바꿔 쓴다
    public LocalDateTime approvedAtKst() {
        return approvedAt == null ? null : approvedAt.atZoneSameInstant(KST).toLocalDateTime();
    }
}
