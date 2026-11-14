package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.boundedContext.payment.app.dto.TopupReconcileResult;
import com.backend.boundedContext.payment.domain.PaymentPolicy;
import com.backend.boundedContext.payment.domain.PgProvider;
import com.backend.boundedContext.payment.out.pg.PgPaymentMethod;
import com.backend.boundedContext.payment.out.pg.PgPaymentNotFoundException;
import com.backend.boundedContext.payment.out.pg.PgPaymentResult;
import com.backend.boundedContext.payment.out.pg.PgPaymentStatus;
import com.backend.boundedContext.payment.out.pg.PgResultUnknownException;
import com.backend.boundedContext.payment.out.pg.TopupGatewayPort;
import com.backend.boundedContext.payment.out.pg.TopupGateways;

class PaymentReconcileMethodCancelingTopupsUseCaseTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 10, 0);
    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 11, 12, 9, 55);
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 11, 12, 9, 50);

    private final PaymentPolicy paymentPolicy = new PaymentPolicy(10_000, 1_000_000, 15, 5, 100, 1L);
    private final PaymentTopupConfirmProcessor processor = mock(PaymentTopupConfirmProcessor.class);
    private final PaymentTopupMethodCanceller canceller = mock(PaymentTopupMethodCanceller.class);
    private final TopupGateways topupGateways = mock(TopupGateways.class);
    private final TopupGatewayPort gateway = mock(TopupGatewayPort.class);
    private final PaymentReconcileMethodCancelingTopupsUseCase useCase =
            new PaymentReconcileMethodCancelingTopupsUseCase(paymentPolicy, processor, canceller, topupGateways);

    @BeforeEach
    void setUp() {
        given(topupGateways.get(PgProvider.TOSS)).willReturn(gateway);
    }

    @Test
    @DisplayName("이미 전액 취소됐으면(앞선 요청의 응답만 못 받음) 다시 요청하지 않고 확정한다")
    void reconcile_alreadyCanceled() {
        targets(target(1));
        given(gateway.getPayment("pk_7")).willReturn(pg(20_000));

        TopupReconcileResult result = useCase.reconcileMethodCancelingTopups(NOW);

        assertThat(result.completed()).isEqualTo(1);
        verify(processor).completeMethodCancel(7L, 1, "허용하지 않은 결제 수단이라 PG에서 결제를 취소했습니다. (OTHER)");
        verify(canceller, never()).retry(anyLong(), anyInt(), any());
    }

    @Test
    @DisplayName("취소되지 않았으면 회차를 늘려 다시 요청한다")
    void reconcile_retry() {
        targets(target(1));
        given(gateway.getPayment("pk_7")).willReturn(pg(0));
        given(canceller.retry(7L, 1, PgPaymentMethod.OTHER)).willReturn(true);

        TopupReconcileResult result = useCase.reconcileMethodCancelingTopups(NOW);

        assertThat(result.completed()).isEqualTo(1);
    }

    @Test
    @DisplayName("다시 요청이 실패하면 failed로 세고, 다음 회차에 또 요청할 수 있게 둔다")
    void reconcile_retryFailed() {
        targets(target(1));
        given(gateway.getPayment("pk_7")).willReturn(pg(0));
        given(canceller.retry(7L, 1, PgPaymentMethod.OTHER)).willReturn(false);

        TopupReconcileResult result = useCase.reconcileMethodCancelingTopups(NOW);

        assertThat(result.failed()).isEqualTo(1);
    }

    @Test
    @DisplayName("최대 횟수(3회)를 채운 건은 다시 요청하지 않는다 (사람이 확인)")
    void reconcile_maxAttempts() {
        targets(target(PaymentReconcileMethodCancelingTopupsUseCase.MAX_ATTEMPTS));
        given(gateway.getPayment("pk_7")).willReturn(pg(0));

        TopupReconcileResult result = useCase.reconcileMethodCancelingTopups(NOW);

        assertThat(result.pending()).isEqualTo(1);
        verify(canceller, never()).retry(anyLong(), anyInt(), any());
    }

    @Test
    @DisplayName("최대 횟수를 채웠어도 PG에서 전액 취소가 확인되면 확정한다 (마지막 요청의 응답만 못 받은 경우)")
    void reconcile_maxAttemptsButCanceled() {
        targets(target(PaymentReconcileMethodCancelingTopupsUseCase.MAX_ATTEMPTS));
        given(gateway.getPayment("pk_7")).willReturn(pg(20_000));

        TopupReconcileResult result = useCase.reconcileMethodCancelingTopups(NOW);

        assertThat(result.completed()).isEqualTo(1);
        verify(processor).completeMethodCancel(7L, 3, "허용하지 않은 결제 수단이라 PG에서 결제를 취소했습니다. (OTHER)");
    }

    @Test
    @DisplayName("일부만 취소됐거나 PG에 결제가 없거나 조회 결과를 모르면 아무것도 하지 않는다")
    void reconcile_cannotDecide() {
        TopupMethodCancelTarget partial = target(1);
        TopupMethodCancelTarget notFound = new TopupMethodCancelTarget(8L, "pk_8", PgProvider.TOSS, 20_000, 1, UPDATED_AT);
        TopupMethodCancelTarget unknown = new TopupMethodCancelTarget(9L, "pk_9", PgProvider.TOSS, 20_000, 1, UPDATED_AT);
        targets(partial, notFound, unknown);
        given(gateway.getPayment("pk_7")).willReturn(pg(5_000));
        given(gateway.getPayment("pk_8")).willThrow(new PgPaymentNotFoundException(new RuntimeException()));
        given(gateway.getPayment("pk_9")).willThrow(new PgResultUnknownException(new RuntimeException("Read timed out")));

        TopupReconcileResult result = useCase.reconcileMethodCancelingTopups(NOW);

        assertThat(result.pending()).isEqualTo(3);
        verify(processor, never()).completeMethodCancel(anyLong(), anyInt(), anyString());
        verify(canceller, never()).retry(anyLong(), anyInt(), any());
    }

    private void targets(TopupMethodCancelTarget... targets) {
        given(processor.findMethodCancelTargets(CUTOFF, TopupReconcileCursor.START, 100)).willReturn(List.of(targets));
    }

    private static TopupMethodCancelTarget target(int attempt) {
        return new TopupMethodCancelTarget(7L, "pk_7", PgProvider.TOSS, 20_000, attempt, UPDATED_AT);
    }

    private static PgPaymentResult pg(long canceledAmount) {
        return new PgPaymentResult("pk_7", "TOPUP-7", PgPaymentStatus.APPROVED, "DONE", 20_000, canceledAmount, PgPaymentMethod.OTHER, null);
    }
}
