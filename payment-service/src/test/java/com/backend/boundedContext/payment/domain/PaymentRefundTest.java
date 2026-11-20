package com.backend.boundedContext.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.shared.order.dto.ReturnReasonCode;

class PaymentRefundTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 17, 10, 0);

    @Test
    @DisplayName("요청: REQUESTED로 시작하고 금액·사유를 남긴다")
    void request() {
        PaymentRefund refund = PaymentRefund.request(55L, 29_500, ReturnReasonCode.DEFECTIVE);

        assertThat(refund.getStatus()).isEqualTo(PaymentRefundStatus.REQUESTED);
        assertThat(refund.getOrderId()).isEqualTo(55L);
        assertThat(refund.getRequestedAmount()).isEqualTo(29_500);
        assertThat(refund.getReason()).isEqualTo(ReturnReasonCode.DEFECTIVE);
        assertThat(refund.getRetryCount()).isZero();
        assertThat(refund.isProcessable()).isTrue();
    }

    @Test
    @DisplayName("단순 변심만 회수 택배비를 내고 상품값만 환불받는다 (기타·불량·사유 없음은 전액 환불)")
    void isChangeOfMind() {
        assertThat(PaymentRefund.request(1L, 1, ReturnReasonCode.CHANGE_OF_MIND).isChangeOfMind()).isTrue();
        assertThat(PaymentRefund.request(1L, 1, ReturnReasonCode.DEFECTIVE).isChangeOfMind()).isFalse();
        assertThat(PaymentRefund.request(1L, 1, ReturnReasonCode.OTHER).isChangeOfMind()).isFalse();
        assertThat(PaymentRefund.request(1L, 1, null).isChangeOfMind()).isFalse();
    }

    @Test
    @DisplayName("완료: 결제 ID·실제 환불 금액·환불 시각을 남기고, 다시 처리할 수 없다")
    void complete() {
        PaymentRefund refund = PaymentRefund.request(55L, 29_500, ReturnReasonCode.DEFECTIVE);

        refund.complete(101L, 29_500, NOW);

        assertThat(refund.getStatus()).isEqualTo(PaymentRefundStatus.COMPLETED);
        assertThat(refund.getPaymentId()).isEqualTo(101L);
        assertThat(refund.getAmount()).isEqualTo(29_500);
        assertThat(refund.getRefundedAt()).isEqualTo(NOW);
        assertThat(refund.isProcessable()).isFalse();
        assertThatThrownBy(() -> refund.complete(101L, 29_500, NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("단순 변심은 결제 대기(PICKUP_FEE_REQUIRED)로 둔다. 회수 택배비를 내기 전에는 완료할 수 없고, 다시 처리 대상도 아니다")
    void requirePickupFee() {
        PaymentRefund refund = PaymentRefund.request(55L, 29_500, ReturnReasonCode.CHANGE_OF_MIND);

        refund.requirePickupFee(101L);

        assertThat(refund.getStatus()).isEqualTo(PaymentRefundStatus.PICKUP_FEE_REQUIRED);
        assertThat(refund.getPaymentId()).isEqualTo(101L);
        assertThat(refund.isProcessable()).isFalse();   // Kafka가 승인 이벤트를 다시 줘도 그대로 둔다
        assertThatThrownBy(() -> refund.complete(101L, 27_000, NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("단순 변심이 아니면 결제 대기로 둘 수 없다")
    void requirePickupFee_onlyChangeOfMind() {
        PaymentRefund refund = PaymentRefund.request(55L, 29_500, ReturnReasonCode.DEFECTIVE);

        assertThatThrownBy(() -> refund.requirePickupFee(101L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("사람이 볼 실패: 사유를 남기고 재시도 횟수는 그대로, 더는 처리할 수 없다")
    void fail_needsPerson() {
        PaymentRefund refund = PaymentRefund.request(55L, 29_500, null);

        refund.fail(PaymentRefundStatus.FAILED_HOLDING_SETTLED, 101L, "홀딩 정산 금액 10,000원");

        assertThat(refund.getStatus()).isEqualTo(PaymentRefundStatus.FAILED_HOLDING_SETTLED);
        assertThat(refund.getPaymentId()).isEqualTo(101L);
        assertThat(refund.getFailReason()).isEqualTo("홀딩 정산 금액 10,000원");
        assertThat(refund.getRetryCount()).isZero();
        assertThat(refund.isProcessable()).isFalse();
    }

    @Test
    @DisplayName("결제를 못 찾은 실패는 결제 ID 없이 남는다")
    void fail_withoutPayment() {
        PaymentRefund refund = PaymentRefund.request(55L, 29_500, null);

        refund.fail(PaymentRefundStatus.FAILED_PAYMENT_NOT_PAID, null, "결제 완료 건 없음");

        assertThat(refund.getPaymentId()).isNull();
    }

    @Test
    @DisplayName("시스템 오류는 실패할 때마다 재시도 횟수가 늘고, 5번 실패하면 더 처리하지 않는다")
    void fail_systemErrorRetriesUpToFive() {
        PaymentRefund refund = PaymentRefund.request(55L, 29_500, null);

        for (int i = 1; i <= PaymentRefund.MAX_SYSTEM_ERROR_RETRIES; i++) {
            assertThat(refund.isProcessable()).isTrue();
            refund.fail(PaymentRefundStatus.FAILED_SYSTEM_ERROR, null, "connection reset");
            assertThat(refund.getRetryCount()).isEqualTo(i);
        }

        assertThat(refund.isProcessable()).isFalse();
    }

    @Test
    @DisplayName("시스템 오류 뒤 재시도에 성공하면 COMPLETED가 되고 실패 사유는 지운다")
    void complete_afterSystemError() {
        PaymentRefund refund = PaymentRefund.request(55L, 29_500, null);
        refund.fail(PaymentRefundStatus.FAILED_SYSTEM_ERROR, null, "lock timeout");

        refund.complete(101L, 29_500, NOW);

        assertThat(refund.getStatus()).isEqualTo(PaymentRefundStatus.COMPLETED);
        assertThat(refund.getFailReason()).isNull();
        assertThat(refund.getRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("실패 사유가 300자를 넘으면 잘라서 남긴다")
    void fail_truncatesReason() {
        PaymentRefund refund = PaymentRefund.request(55L, 29_500, null);

        refund.fail(PaymentRefundStatus.FAILED_SYSTEM_ERROR, null, "x".repeat(500));

        assertThat(refund.getFailReason()).hasSize(300);
    }

    @Test
    @DisplayName("실패 상태가 아닌 값으로 fail을 부르면 막는다")
    void fail_rejectsNonFailureStatus() {
        PaymentRefund refund = PaymentRefund.request(55L, 29_500, null);

        assertThatThrownBy(() -> refund.fail(PaymentRefundStatus.COMPLETED, null, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
