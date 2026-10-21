package com.backend.boundedContext.payment.app.dto;

import java.time.LocalDateTime;

import com.backend.boundedContext.payment.domain.Payment;
import com.backend.boundedContext.payment.domain.PaymentStatus;
import com.backend.boundedContext.payment.domain.WalletHoldingStatus;

import io.swagger.v3.oas.annotations.media.Schema;

// 관리자 결제 조회의 결제 탭 항목 (결제 시도 1건)
@Schema(description = "관리자 결제 조회의 결제 탭 항목. 성공·실패를 포함한 결제 시도 1건")
public record AdminPaymentDto(
        @Schema(description = "결제 건 ID", example = "12")
        Long paymentId,

        @Schema(description = "주문 ID", example = "142")
        Long orderId,

        @Schema(description = "주문번호. 결제 시점에 저장한 값 (개발용 시드 결제 건은 SEED-ORDER-1 형식)", example = "ORD-20261009-A1B2C3D4")
        String orderNumber,

        @Schema(description = "구매자 회원 ID", example = "3")
        Long memberId,

        @Schema(description = "구매자 이메일. 회원을 찾을 수 없으면 null", example = "buyer@palette.test")
        String memberEmail,

        @Schema(description = "결제 금액", example = "72900")
        long amount,

        @Schema(description = """
                결제 상태
                - PAID: 결제 완료
                - REFUNDED: 환불
                - FAILED_INSUFFICIENT_BALANCE: 잔액 부족
                - FAILED_ORDER_EXPIRED: 주문 만료
                - FAILED_SYSTEM_ERROR: 시스템 오류""", example = "PAID")
        PaymentStatus status,

        @Schema(description = "실패 사유. 실패한 결제만 값이 있음")
        String failReason,

        @Schema(description = """
                보관 금액 상태. 결제 완료 건만 값이 있고 실패 건은 null
                - HELD: 보관 중
                - RELEASED: 환불 완료
                - SETTLED: 정산됨""", example = "HELD")
        WalletHoldingStatus holdingStatus,

        @Schema(description = "결제 시도 시각 (화면의 결제일)", example = "2026-10-09T14:06:00")
        LocalDateTime createdAt,

        @Schema(description = "결제 완료 시각. 결제 완료 건만 값이 있음", example = "2026-10-09T14:06:00")
        LocalDateTime paidAt
) {
    public static AdminPaymentDto of(Payment payment, String memberEmail, WalletHoldingStatus holdingStatus) {
        return new AdminPaymentDto(payment.getId(), payment.getOrderId(), payment.getOrderNumber(), payment.getMemberId(), memberEmail,
                payment.getAmount(), payment.getStatus(), payment.getFailReason(), holdingStatus,
                payment.getCreatedAt(), payment.getPaidAt());
    }
}
