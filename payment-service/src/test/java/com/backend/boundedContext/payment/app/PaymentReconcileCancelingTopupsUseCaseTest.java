package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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

class PaymentReconcileCancelingTopupsUseCaseTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 10, 0);
    // 30,000원 충전 건, 1회차에 20,000원 취소 확정, 2회차에 10,000원 취소 요청 중
    private static final TopupCancelReconcileTarget TARGET = new TopupCancelReconcileTarget(7L, "pk_7", PgProvider.TOSS, 2, 20_000, 10_000);

    private final PaymentPolicy paymentPolicy = new PaymentPolicy(10_000, 1_000_000, 15, 5, 100, 1L);
    private final PaymentTopupCancelProcessor processor = mock(PaymentTopupCancelProcessor.class);
    private final TopupGateways topupGateways = mock(TopupGateways.class);
    private final TopupGatewayPort gateway = mock(TopupGatewayPort.class);
    private final PaymentReconcileCancelingTopupsUseCase useCase =
            new PaymentReconcileCancelingTopupsUseCase(paymentPolicy, processor, topupGateways);

    @BeforeEach
    void setUp() {
        given(topupGateways.get(PgProvider.TOSS)).willReturn(gateway);
    }

    @Test
    @DisplayName("지금보다 확인 대기 시간(5분) 이전부터 CANCELING인 건을 최대 1회 처리 건수만큼 조회한다")
    void findTargets() {
        given(processor.findReconcileTargets(LocalDateTime.of(2026, 11, 12, 9, 55), 100)).willReturn(List.of());

        TopupReconcileResult result = useCase.reconcileCancelingTopups(NOW);

        assertThat(result.total()).isZero();
    }

    @Test
    @DisplayName("PG 취소 합계가 이전 취소 + 이번 취소 금액이면 이번 회차의 취소를 확정한다 (PG 상태가 PARTIAL_CANCELED여도)")
    void reconcile_completed() {
        targets(TARGET);
        given(gateway.getPayment("pk_7")).willReturn(pg(PgPaymentStatus.PARTIALLY_CANCELED, 30_000));

        TopupReconcileResult result = useCase.reconcileCancelingTopups(NOW);

        assertThat(result.completed()).isEqualTo(1);
        verify(processor).complete(7L, 2, 10_000);
        verify(processor, never()).fail(anyLong(), anyInt());
    }

    @Test
    @DisplayName("PG 취소 합계가 이전 취소 금액 그대로면 이번 취소가 반영되지 않은 것으로 보고 되돌린다")
    void reconcile_notCanceled() {
        targets(TARGET);
        given(gateway.getPayment("pk_7")).willReturn(pg(PgPaymentStatus.PARTIALLY_CANCELED, 20_000));

        TopupReconcileResult result = useCase.reconcileCancelingTopups(NOW);

        assertThat(result.failed()).isEqualTo(1);
        verify(processor).fail(7L, 2);
        verify(processor, never()).complete(anyLong(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("PG 취소 합계가 이전 취소와 이전 + 이번 취소 사이면 자동으로 정하지 않고 CANCELING으로 둔다")
    void reconcile_mismatch() {
        targets(TARGET);
        given(gateway.getPayment("pk_7")).willReturn(pg(PgPaymentStatus.PARTIALLY_CANCELED, 25_000));

        TopupReconcileResult result = useCase.reconcileCancelingTopups(NOW);

        assertThat(result.pending()).isEqualTo(1);
        verify(processor, never()).complete(anyLong(), anyInt(), anyLong());
        verify(processor, never()).fail(anyLong(), anyInt());
    }

    @Test
    @DisplayName("PG 취소 합계가 이전 + 이번 취소보다 많으면(따로 취소된 금액) 확정하지 않고 CANCELING으로 둔다")
    void reconcile_moreThanExpected() {
        targets(TARGET);
        given(gateway.getPayment("pk_7")).willReturn(pg(PgPaymentStatus.PARTIALLY_CANCELED, 35_000));

        TopupReconcileResult result = useCase.reconcileCancelingTopups(NOW);

        assertThat(result.pending()).isEqualTo(1);
        verify(processor, never()).complete(anyLong(), anyInt(), anyLong());
        verify(processor, never()).fail(anyLong(), anyInt());
    }

    @Test
    @DisplayName("PG에 결제가 없거나 조회 결과를 모르면 되돌리지 않고 CANCELING으로 둔다")
    void reconcile_notFoundOrUnknown() {
        TopupCancelReconcileTarget other = new TopupCancelReconcileTarget(8L, "pk_8", PgProvider.TOSS, 1, 0, 20_000);
        targets(TARGET, other);
        given(gateway.getPayment("pk_7")).willThrow(new PgPaymentNotFoundException(new RuntimeException()));
        given(gateway.getPayment("pk_8")).willThrow(new PgResultUnknownException(new RuntimeException("Read timed out")));

        TopupReconcileResult result = useCase.reconcileCancelingTopups(NOW);

        assertThat(result.pending()).isEqualTo(2);
        verify(processor, never()).complete(anyLong(), anyInt(), anyLong());
        verify(processor, never()).fail(anyLong(), anyInt());
    }

    @Test
    @DisplayName("한 건에서 오류가 나도 나머지 건은 계속 처리한다")
    void reconcile_errorDoesNotStopOthers() {
        TopupCancelReconcileTarget other = new TopupCancelReconcileTarget(8L, "pk_8", PgProvider.TOSS, 1, 0, 20_000);
        targets(TARGET, other);
        given(gateway.getPayment("pk_7")).willThrow(new IllegalStateException("예상 못 한 오류"));
        given(gateway.getPayment("pk_8")).willReturn(pg(PgPaymentStatus.CANCELED, 20_000));

        TopupReconcileResult result = useCase.reconcileCancelingTopups(NOW);

        assertThat(result.errors()).isEqualTo(1);
        assertThat(result.completed()).isEqualTo(1);
        verify(processor).complete(8L, 1, 20_000);
    }

    private void targets(TopupCancelReconcileTarget... targets) {
        given(processor.findReconcileTargets(LocalDateTime.of(2026, 11, 12, 9, 55), 100)).willReturn(List.of(targets));
    }

    private static PgPaymentResult pg(PgPaymentStatus status, long canceledAmount) {
        return new PgPaymentResult("pk", "TOPUP-x", status, status.name(), 30_000, canceledAmount, PgPaymentMethod.CARD, null);
    }
}
