package com.backend.boundedContext.payment.out.pg;

import java.time.LocalDateTime;

import com.backend.boundedContext.payment.domain.Topup;

/**
 * PG에 결제를 조회할 때 쓰는 충전 건 정보. PG마다 결제를 찾는 값이 달라 필요한 값을 함께 넘김
 * - 토스: 결제키로 조회
 * - 헥토: 주문번호·원거래 일시(결제 요청 시각)·금액으로 조회. 못 찾으면 승인 요청 시각 날짜로 한 번 더 조회 (자정을 넘긴 충전 건)
 */
public record PgPaymentRef(
        String orderId,
        String paymentKey,   // PROCESSING 선점(Tx1) 때 저장한 결제키
        long amount,
        LocalDateTime requestedAt,
        LocalDateTime confirmRequestedAt   // PROCESSING 선점 때 저장한 승인 요청 시각. 그 전이거나 V9 전 충전 건이면 null
) {
    public static PgPaymentRef from(Topup topup) {
        return new PgPaymentRef(topup.getOrderId(), topup.getPaymentKey(), topup.getAmount(), topup.getRequestedAt(), topup.getConfirmRequestedAt());
    }
}
