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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.boundedContext.payment.app.dto.TopupReconcileResult;
import com.backend.boundedContext.payment.domain.PaymentPolicy;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.out.toss.TossPaymentException;
import com.backend.boundedContext.payment.out.toss.TossPaymentResponse;
import com.backend.boundedContext.payment.out.toss.TossPaymentsClient;
import com.backend.boundedContext.payment.out.toss.TossTimeoutException;

class PaymentReconcileTopupsUseCaseTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 10, 0);
    private static final TopupReconcileTarget TARGET = new TopupReconcileTarget(7L, "TOPUP-7");

    private final PaymentPolicy paymentPolicy = new PaymentPolicy(10_000, 1_000_000, 15, 5, 100, 1L);
    private final PaymentTopupConfirmProcessor processor = mock(PaymentTopupConfirmProcessor.class);
    private final TossPaymentsClient tossPaymentsClient = mock(TossPaymentsClient.class);
    private final PaymentReconcileTopupsUseCase useCase =
            new PaymentReconcileTopupsUseCase(paymentPolicy, processor, tossPaymentsClient);

    @Test
    @DisplayName("지금보다 확인 대기 시간(5분) 이전부터 PROCESSING인 건을 최대 1회 처리 건수만큼 조회한다")
    void findTargets() {
        given(processor.findReconcileTargets(LocalDateTime.of(2026, 10, 14, 9, 55), 100)).willReturn(List.of());

        TopupReconcileResult result = useCase.reconcileProcessingTopups(NOW);

        assertThat(result.total()).isZero();
        verify(processor).findReconcileTargets(LocalDateTime.of(2026, 10, 14, 9, 55), 100);
    }

    @Test
    @DisplayName("토스에서 승인(DONE)됐으면 예치금에 반영한다")
    void approved() {
        targets(TARGET);
        TossPaymentResponse toss = toss("DONE");
        given(tossPaymentsClient.getPaymentByOrderId("TOPUP-7")).willReturn(toss);

        TopupReconcileResult result = useCase.reconcileProcessingTopups(NOW);

        verify(processor).complete(7L, toss);
        assertThat(result.completed()).isEqualTo(1);
    }

    @Test
    @DisplayName("토스에서 승인되지 않음(ABORTED·EXPIRED)이 확정되면 FAILED_PG_DECLINED로 기록한다")
    void notApproved() {
        targets(TARGET, new TopupReconcileTarget(8L, "TOPUP-8"));
        given(tossPaymentsClient.getPaymentByOrderId("TOPUP-7")).willReturn(toss("ABORTED"));
        given(tossPaymentsClient.getPaymentByOrderId("TOPUP-8")).willReturn(toss("EXPIRED"));

        TopupReconcileResult result = useCase.reconcileProcessingTopups(NOW);

        verify(processor).fail(eq(7L), eq(TopupStatus.FAILED_PG_DECLINED), anyString());
        verify(processor).fail(eq(8L), eq(TopupStatus.FAILED_PG_DECLINED), anyString());
        assertThat(result.failed()).isEqualTo(2);
    }

    @Test
    @DisplayName("토스에서 취소(CANCELED)된 결제면 반영하지 않고 FAILED_SYSTEM_ERROR로 기록한다")
    void canceled() {
        targets(TARGET);
        given(tossPaymentsClient.getPaymentByOrderId("TOPUP-7")).willReturn(toss("CANCELED"));

        TopupReconcileResult result = useCase.reconcileProcessingTopups(NOW);

        verify(processor).fail(eq(7L), eq(TopupStatus.FAILED_SYSTEM_ERROR), anyString());
        verify(processor, never()).complete(anyLong(), any());
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test
    @DisplayName("토스에 결제가 없으면(404 NOT_FOUND_PAYMENT) FAILED_SYSTEM_ERROR로 기록한다")
    void notFoundPayment() {
        targets(TARGET);
        given(tossPaymentsClient.getPaymentByOrderId("TOPUP-7"))
                .willThrow(new TossPaymentException(404, "NOT_FOUND_PAYMENT", "존재하지 않는 결제 정보 입니다.", new RuntimeException()));

        TopupReconcileResult result = useCase.reconcileProcessingTopups(NOW);

        verify(processor).fail(eq(7L), eq(TopupStatus.FAILED_SYSTEM_ERROR), anyString());
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test
    @DisplayName("토스 응답이 없거나 5xx이거나 아직 진행 중이면 PROCESSING으로 두고 다음 회차에 다시 확인한다")
    void pending() {
        targets(TARGET, new TopupReconcileTarget(8L, "TOPUP-8"), new TopupReconcileTarget(9L, "TOPUP-9"));
        given(tossPaymentsClient.getPaymentByOrderId("TOPUP-7"))
                .willThrow(new TossTimeoutException(new RuntimeException("Read timed out")));
        given(tossPaymentsClient.getPaymentByOrderId("TOPUP-8"))
                .willThrow(new TossPaymentException(500, "FAILED_INTERNAL_SYSTEM_PROCESSING", "내부 오류", new RuntimeException()));
        given(tossPaymentsClient.getPaymentByOrderId("TOPUP-9")).willReturn(toss("IN_PROGRESS"));

        TopupReconcileResult result = useCase.reconcileProcessingTopups(NOW);

        verify(processor, never()).complete(anyLong(), any());
        verify(processor, never()).fail(anyLong(), any(), anyString());
        assertThat(result.pending()).isEqualTo(3);
    }

    @Test
    @DisplayName("한 건에서 오류가 나도 나머지 건은 계속 처리한다")
    void errorDoesNotStopOthers() {
        targets(TARGET, new TopupReconcileTarget(8L, "TOPUP-8"));
        TossPaymentResponse done7 = toss("DONE");
        TossPaymentResponse done8 = toss("DONE");
        given(tossPaymentsClient.getPaymentByOrderId("TOPUP-7")).willReturn(done7);
        given(tossPaymentsClient.getPaymentByOrderId("TOPUP-8")).willReturn(done8);
        given(processor.complete(7L, done7)).willThrow(new RuntimeException("DB 오류"));

        TopupReconcileResult result = useCase.reconcileProcessingTopups(NOW);

        verify(processor).complete(8L, done8);
        assertThat(result.errors()).isEqualTo(1);
        assertThat(result.completed()).isEqualTo(1);
    }

    private void targets(TopupReconcileTarget... targets) {
        given(processor.findReconcileTargets(any(), eq(100))).willReturn(List.of(targets));
    }

    private static TossPaymentResponse toss(String status) {
        return new TossPaymentResponse("pk_1", "TOPUP-x", status, 20_000, "카드", null);
    }
}
