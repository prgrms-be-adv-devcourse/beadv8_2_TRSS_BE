package com.backend.boundedContext.payment.app.dto;

import java.time.LocalDateTime;

import com.backend.boundedContext.payment.domain.PgProvider;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupManualCheckReason;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.out.pg.PgPaymentResult;

import io.swagger.v3.oas.annotations.media.Schema;

// 관리자 충전 PG 결제 조회: 우리 기록과 그 자리에서 PG에 조회한 결과를 나란히 보여 줌
@Schema(description = "관리자 충전 PG 결제 조회. 우리 기록(충전 건)과 PG 조회 결과")
public record AdminTopupPgPaymentDto(
        @Schema(description = "충전 건 ID", example = "14")
        Long topupId,

        @Schema(description = "충전번호", example = "TOPUP-88e21df8-bddb-4ff6-9ea1-dd5e1cc3cc04")
        String orderId,

        @Schema(description = "결제한 PG", example = "TOSS")
        PgProvider pgProvider,

        @Schema(description = "충전 상태", example = "CANCELING")
        TopupStatus status,

        @Schema(description = "충전 금액", example = "30000")
        long amount,

        @Schema(description = "결제에 쓰이고 남은 금액", example = "0")
        long remainingAmount,

        @Schema(description = "PG에 취소를 요청 중인 금액. CANCELING 동안만 0보다 큼", example = "10000")
        long cancelingAmount,

        @Schema(description = "PG 취소가 확정된 금액 합계", example = "20000")
        long canceledAmount,

        @Schema(description = "취소 회차", example = "2")
        int cancelAttempts,

        @Schema(description = """
                수동 확인 사유. 표시되지 않았으면 null
                - CANCEL_AMOUNT_MISMATCH: PG 취소 합계가 이전 취소와도, 이전 + 이번 취소와도 다름
                - PG_PAYMENT_NOT_FOUND: PG에 결제가 없음
                - METHOD_CANCEL_PARTIAL: 허용하지 않은 결제 수단 건이 일부만 취소됨
                - METHOD_CANCEL_RETRY_EXHAUSTED: 허용하지 않은 결제 수단 건의 PG 취소를 최대 횟수만큼 시도했지만 취소되지 않음""", example = "CANCEL_AMOUNT_MISMATCH")
        TopupManualCheckReason manualCheckReason,

        @Schema(description = "수동 확인으로 표시한 시각", example = "2026-11-18T21:05:00")
        LocalDateTime manualCheckAt,

        @Schema(description = "PG 조회 결과")
        PgPayment pg
) {
    public static AdminTopupPgPaymentDto of(Topup topup, PgPayment pg) {
        return new AdminTopupPgPaymentDto(topup.getId(), topup.getOrderId(), topup.getPgProvider(), topup.getStatus(), topup.getAmount(),
                topup.getRemainingAmount(), topup.getCancelingAmount(), topup.getCanceledAmount(), topup.getCancelAttempts(),
                topup.getManualCheckReason(), topup.getManualCheckAt(), pg);
    }

    @Schema(description = "PG 조회 결과. result가 FOUND일 때만 상태·금액이 있음")
    public record PgPayment(
            @Schema(description = """
                    - FOUND: PG에 결제가 있음
                    - NOT_FOUND: PG에 결제가 없음
                    - UNKNOWN: 조회 결과를 모름 (응답 없음·설정 오류 등). message에 원인
                    - NOT_REQUESTED: 승인 요청 전이라 조회하지 않음 (결제키 없음)""", example = "FOUND")
            String result,

            @Schema(description = "PG 결제 상태 (APPROVED, CANCELED, PARTIALLY_CANCELED 등)", example = "PARTIALLY_CANCELED")
            String status,

            @Schema(description = "PG가 준 원래 상태값", example = "PARTIAL_CANCELED")
            String pgStatus,

            @Schema(description = "PG 결제 금액", example = "30000")
            Long totalAmount,

            @Schema(description = "PG 취소 금액 합계", example = "25000")
            Long canceledAmount,

            @Schema(description = "결제 수단", example = "CARD")
            String method,

            @Schema(description = "조회하지 못한 원인. UNKNOWN일 때만 값이 있음")
            String message
    ) {
        public static PgPayment found(PgPaymentResult result) {
            return new PgPayment("FOUND", result.status().name(), result.pgStatus(), result.totalAmount(), result.canceledAmount(),
                    result.method() == null ? null : result.method().name(), null);
        }

        public static PgPayment notFound() {
            return new PgPayment("NOT_FOUND", null, null, null, null, null, null);
        }

        public static PgPayment unknown(String message) {
            return new PgPayment("UNKNOWN", null, null, null, null, null, message);
        }

        public static PgPayment notRequested() {
            return new PgPayment("NOT_REQUESTED", null, null, null, null, null, null);
        }
    }
}
