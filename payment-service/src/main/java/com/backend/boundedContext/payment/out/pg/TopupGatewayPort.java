package com.backend.boundedContext.payment.out.pg;

import com.backend.boundedContext.payment.domain.PgProvider;

/**
 * 충전에서 쓰는 PG 기능만 담은 Port. 충전 로직이 특정 PG의 API·응답 형식·오류 코드를 모르게 함
 * 구현체: TossTopupGatewayAdapter (토스페이먼츠)
 *
 * 실패는 결과로 판단할 수 있는 예외 종류로만 알림
 * - PgResultUnknownException : 응답 없음·5xx·이미 처리된 결제 → 승인·취소됐는지 알 수 없음
 * - PgDeclinedException : 카드 거절·취소 불가 등 확정된 실패
 * - PgConfigurationException : API 키 설정 오류 (우리 문제)
 * - PgPaymentNotFoundException : 조회했는데 PG에 결제가 없음
 */
public interface TopupGatewayPort {

    PgProvider provider();

    // 결제 승인. 결제창 인증을 마친 결제를 최종 승인하며, 성공하면 실제로 돈이 빠져나감
    PgPaymentResult confirm(String paymentKey, String orderId, long amount);

    // 주문번호로 결제 조회 (결과를 모르는 PROCESSING·CANCELING 충전 건의 최종 결과 확인용)
    PgPaymentResult getPaymentByOrderId(String orderId);

    // 결제 취소. cancelAmount만큼 부분 취소하며, 같은 idempotencyKey로 다시 요청하면 PG가 한 번만 취소함
    PgPaymentResult cancel(String paymentKey, long cancelAmount, String reason, String idempotencyKey);
}
