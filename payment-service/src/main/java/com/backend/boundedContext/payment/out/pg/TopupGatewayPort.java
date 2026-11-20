package com.backend.boundedContext.payment.out.pg;

import java.util.Optional;

import com.backend.boundedContext.payment.domain.PgProvider;

/**
 * 충전에서 쓰는 PG 기능만 담은 Port. 충전 로직이 특정 PG의 API·응답 형식·오류 코드를 모르게 함
 * 구현체: TossTopupGatewayAdapter (토스페이먼츠), HectoTopupGatewayAdapter (헥토파이낸셜)
 *
 * 실패는 결과로 판단할 수 있는 예외 종류로만 알림
 * - PgResultUnknownException : 응답 없음·5xx·이미 처리된 결제 → 승인·취소됐는지 알 수 없음
 * - PgDeclinedException : 카드 거절·취소 불가 등 확정된 실패
 * - PgConfigurationException : API 키 설정 오류 (우리 문제)
 * - PgPaymentNotFoundException : 조회했는데 PG에 결제가 없음
 */
public interface TopupGatewayPort {

    PgProvider provider();

    // 결제창을 띄우는 데 필요한 값. 서버에서 만들 값이 없는 PG(토스는 SDK가 주문번호·금액으로 띄움)는 빈 값
    Optional<PgCheckout> checkout(PgPaymentRef payment);

    // 결제창 실패 코드가 사용자 취소(결제창 닫기 등)인지. 코드는 PG마다 달라 어댑터가 판단함
    boolean isUserCancel(String failCode);

    // 결제 승인. 결제창 인증을 마친 결제를 최종 승인하며, 성공하면 실제로 돈이 빠져나감
    PgPaymentResult confirm(String paymentKey, String orderId, long amount);

    /**
     * 승인 결과를 모를 때 조회해 거래가 없으면(처리되지 않음) 승인을 한 번 다시 보내도 되는지
     * PG가 같은 결제의 두 번째 승인을 거절해 이중 결제가 생기지 않는 경우만 true (헥토는 ST50으로 거절함을 테스트베드에서 확인)
     */
    boolean canRetryConfirm();

    // 결제 조회 (결과를 모르는 PROCESSING·CANCELING 충전 건의 최종 결과 확인용). 결제를 찾는 값은 PG마다 달라 충전 건 정보를 넘김
    PgPaymentResult getPayment(PgPaymentRef payment);

    /**
     * 결제 취소. cancelAmount만큼 부분 취소함
     * 거절이면 잔액을 되돌리므로, 취소됐을 가능성이 있는 응답(이미 취소됨·취소 금액 초과·처리 중)은 PgResultUnknownException으로 분류함
     * 멱등 키를 지원하지 않는 PG가 있음 (토스는 같은 idempotencyKey면 한 번만 취소하지만, 헥토는 같은 회차를 다시 보내면 또 취소됨)
     * 그래서 결과를 모르는 취소는 다시 요청하지 않고, 대사가 PG 조회로 취소 합계를 확인해 확정·되돌림
     */
    PgPaymentResult cancel(PgCancelRequest request);
}
