package com.backend.boundedContext.payment.out.pg;

import java.util.Optional;

import com.backend.boundedContext.payment.domain.PgProvider;

/**
 * PG 포트를 감싸 PG를 부르는 호출(승인·조회·취소)의 결과를 서킷 브레이커에 기록함. 호출은 막지 않음
 * 결제창 요청 값·사용자 취소 판단은 PG를 부르지 않아 기록하지 않음
 */
class CircuitRecordingTopupGateway implements TopupGatewayPort {

    private final TopupGatewayPort delegate;   // 감싼 실제 PG 어댑터 (토스·헥토). PG 일은 모두 여기에 맡김
    private final PgCircuitBreakers circuitBreakers;

    CircuitRecordingTopupGateway(TopupGatewayPort delegate, PgCircuitBreakers circuitBreakers) {
        this.delegate = delegate;
        this.circuitBreakers = circuitBreakers;
    }

    @Override
    public PgProvider provider() {
        return delegate.provider();
    }

    @Override
    public Optional<PgCheckout> checkout(PgPaymentRef payment) {
        return delegate.checkout(payment);
    }

    @Override
    public boolean isUserCancel(String failCode) {
        return delegate.isUserCancel(failCode);
    }

    @Override
    public PgPaymentResult confirm(String paymentKey, String orderId, long amount) {
        return circuitBreakers.record(provider(), () -> delegate.confirm(paymentKey, orderId, amount));
    }

    @Override
    public boolean canRetryConfirm() {
        return delegate.canRetryConfirm();
    }

    @Override
    public PgPaymentResult getPayment(PgPaymentRef payment) {
        return circuitBreakers.record(provider(), () -> delegate.getPayment(payment));
    }

    @Override
    public PgPaymentResult cancel(PgCancelRequest request) {
        return circuitBreakers.record(provider(), () -> delegate.cancel(request));
    }
}
