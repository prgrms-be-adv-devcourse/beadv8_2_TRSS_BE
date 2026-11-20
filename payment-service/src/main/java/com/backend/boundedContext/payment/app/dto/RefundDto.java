package com.backend.boundedContext.payment.app.dto;

import java.time.LocalDateTime;

import com.backend.boundedContext.payment.domain.Payment;
import com.backend.boundedContext.payment.domain.PaymentRefund;
import com.backend.boundedContext.payment.domain.PaymentRefundStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 구매자에게 보여 주는 반품 환불 한 건
 *
 * @param refundAmount 돌려받는(받은) 금액. 완료 전이면 받을 예정 금액(단순 변심은 상품값만, 그 밖에는 결제 금액 전부)
 * @param pickupFee    내야 하는(낸) 반품 회수 택배비. 단순 변심만, 그 밖에는 0
 * @param refundedAt   환불 시각. 완료 전이면 null
 */
public record RefundDto(
        Long orderId,
        @Schema(description = """
                REQUESTED: 처리 중 / PICKUP_FEE_REQUIRED: 회수 택배비를 결제하면 환불 / COMPLETED: 환불 완료 /
                FAILED_*: 환불 실패(관리자가 확인)""")
        PaymentRefundStatus status,
        long refundAmount,
        long pickupFee,
        LocalDateTime refundedAt
) {
    public static RefundDto of(PaymentRefund refund, Payment payment, long pickupFee) {
        boolean completed = refund.getStatus() == PaymentRefundStatus.COMPLETED;
        return new RefundDto(
                refund.getOrderId(),
                refund.getStatus(),
                completed ? refund.getAmount() : payment.refundAmount(refund.isChangeOfMind()),
                !refund.isChangeOfMind() ? 0 : completed ? refund.getReturnPickupFee() : pickupFee,
                refund.getRefundedAt());
    }
}
