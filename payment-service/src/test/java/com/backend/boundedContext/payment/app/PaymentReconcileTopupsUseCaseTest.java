package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.out.pg.PgPaymentMethod;
import com.backend.boundedContext.payment.out.pg.PgPaymentNotFoundException;
import com.backend.boundedContext.payment.out.pg.PgPaymentResult;
import com.backend.boundedContext.payment.out.pg.PgPaymentStatus;
import com.backend.boundedContext.payment.out.pg.PgResultUnknownException;
import com.backend.boundedContext.payment.out.pg.TopupGatewayPort;
import com.backend.boundedContext.payment.out.pg.TopupGateways;

class PaymentReconcileTopupsUseCaseTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 10, 0);
    private static final TopupReconcileTarget TARGET = new TopupReconcileTarget(7L, "TOPUP-7", PgProvider.TOSS);

    private final PaymentPolicy paymentPolicy = new PaymentPolicy(10_000, 1_000_000, 15, 5, 100, 1L);
    private final PaymentTopupConfirmProcessor processor = mock(PaymentTopupConfirmProcessor.class);
    private final TopupGateways topupGateways = mock(TopupGateways.class);
    private final TopupGatewayPort gateway = mock(TopupGatewayPort.class);
    private final PaymentReconcileTopupsUseCase useCase =
            new PaymentReconcileTopupsUseCase(paymentPolicy, processor, topupGateways);

    @BeforeEach
    void setUp() {
        given(topupGateways.get(PgProvider.TOSS)).willReturn(gateway);
    }

    @Test
    @DisplayName("지금보다 확인 대기 시간(5분) 이전부터 PROCESSING인 건을 최대 1회 처리 건수만큼 조회한다")
    void findTargets() {
        given(processor.findReconcileTargets(LocalDateTime.of(2026, 10, 14, 9, 55), 100)).willReturn(List.of());

        TopupReconcileResult result = useCase.reconcileProcessingTopups(NOW);

        assertThat(result.total()).isZero();
        verify(processor).findReconcileTargets(LocalDateTime.of(2026, 10, 14, 9, 55), 100);
    }

    @Test
    @DisplayName("결제한 PG에서 승인(APPROVED)됐으면 예치금에 반영한다")
    void approved() {
        targets(TARGET);
        PgPaymentResult approved = pg(PgPaymentStatus.APPROVED);
        given(gateway.getPaymentByOrderId("TOPUP-7")).willReturn(approved);

        TopupReconcileResult result = useCase.reconcileProcessingTopups(NOW);

        verify(topupGateways).get(PgProvider.TOSS);
        verify(processor).complete(7L, approved);
        assertThat(result.completed()).isEqualTo(1);
    }

    @Test
    @DisplayName("PG에서 승인되지 않음(NOT_APPROVED)이 확정되면 FAILED_PG_DECLINED로 기록한다")
    void notApproved() {
        targets(TARGET);
        given(gateway.getPaymentByOrderId("TOPUP-7")).willReturn(pg(PgPaymentStatus.NOT_APPROVED));

        TopupReconcileResult result = useCase.reconcileProcessingTopups(NOW);

        verify(processor).fail(7L, TopupStatus.FAILED_PG_DECLINED, "PG에서 결제가 승인되지 않았습니다.");
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test
    @DisplayName("PG에서 취소(CANCELED)된 결제면 반영하지 않고 FAILED_SYSTEM_ERROR로 기록한다")
    void canceled() {
        targets(TARGET);
        given(gateway.getPaymentByOrderId("TOPUP-7")).willReturn(pg(PgPaymentStatus.CANCELED));

        TopupReconcileResult result = useCase.reconcileProcessingTopups(NOW);

        verify(processor).fail(7L, TopupStatus.FAILED_SYSTEM_ERROR, "PG에서 취소된 결제입니다.");
        verify(processor, never()).complete(anyLong(), any());
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test
    @DisplayName("PG에 결제가 없으면 FAILED_SYSTEM_ERROR로 기록한다")
    void notFoundPayment() {
        targets(TARGET);
        given(gateway.getPaymentByOrderId("TOPUP-7")).willThrow(new PgPaymentNotFoundException(new RuntimeException()));

        TopupReconcileResult result = useCase.reconcileProcessingTopups(NOW);

        verify(processor).fail(7L, TopupStatus.FAILED_SYSTEM_ERROR, "PG에 결제 내역이 없어 승인되지 않은 것으로 처리했습니다.");
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test
    @DisplayName("PG 결과를 알 수 없거나 아직 확정되지 않았으면(PENDING) PROCESSING으로 두고 다음 회차에 다시 확인한다")
    void pending() {
        targets(TARGET, new TopupReconcileTarget(8L, "TOPUP-8", PgProvider.TOSS));
        given(gateway.getPaymentByOrderId("TOPUP-7"))
                .willThrow(new PgResultUnknownException(new RuntimeException("Read timed out")));
        given(gateway.getPaymentByOrderId("TOPUP-8")).willReturn(pg(PgPaymentStatus.PENDING));

        TopupReconcileResult result = useCase.reconcileProcessingTopups(NOW);

        verify(processor, never()).complete(anyLong(), any());
        verify(processor, never()).fail(anyLong(), any(), anyString());
        assertThat(result.pending()).isEqualTo(2);
    }

    @Test
    @DisplayName("한 건에서 오류가 나도 나머지 건은 계속 처리한다")
    void errorDoesNotStopOthers() {
        targets(TARGET, new TopupReconcileTarget(8L, "TOPUP-8", PgProvider.TOSS));
        PgPaymentResult done7 = pg(PgPaymentStatus.APPROVED, "TOPUP-7");
        PgPaymentResult done8 = pg(PgPaymentStatus.APPROVED, "TOPUP-8");
        given(gateway.getPaymentByOrderId("TOPUP-7")).willReturn(done7);
        given(gateway.getPaymentByOrderId("TOPUP-8")).willReturn(done8);
        given(processor.complete(7L, done7)).willThrow(new RuntimeException("DB 오류"));

        TopupReconcileResult result = useCase.reconcileProcessingTopups(NOW);

        verify(processor).complete(8L, done8);
        assertThat(result.errors()).isEqualTo(1);
        assertThat(result.completed()).isEqualTo(1);
    }

    private void targets(TopupReconcileTarget... targets) {
        given(processor.findReconcileTargets(any(), eq(100))).willReturn(List.of(targets));
    }

    private static PgPaymentResult pg(PgPaymentStatus status) {
        return pg(status, "TOPUP-x");
    }

    private static PgPaymentResult pg(PgPaymentStatus status, String orderId) {
        return new PgPaymentResult("pk_1", orderId, status, 20_000, PgPaymentMethod.CARD, null);
    }
}
