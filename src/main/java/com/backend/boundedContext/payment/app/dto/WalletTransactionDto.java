package com.backend.boundedContext.payment.app.dto;

import java.time.LocalDateTime;

import com.backend.boundedContext.payment.domain.WalletTransaction;
import com.backend.boundedContext.payment.domain.WalletTransactionReferenceType;
import com.backend.boundedContext.payment.domain.WalletTransactionType;

import io.swagger.v3.oas.annotations.media.Schema;

// 내 예치금 거래 내역 항목
@Schema(description = "예치금 거래 내역 항목. 지갑 잔액이 바뀔 때마다 한 건씩 남는다")
public record WalletTransactionDto(
        @Schema(description = "거래 내역 ID", example = "60")
        Long transactionId,

        @Schema(description = """
                거래 유형
                - CHARGE: 충전
                - CHARGE_CANCEL: 미사용 충전 취소
                - PAYMENT: 주문 결제
                - REFUND: 반품 환불
                - SETTLEMENT: 정산 지급 (판매자)
                - FEE: 정산 수수료 (관리자)""", example = "PAYMENT")
        WalletTransactionType type,

        @Schema(description = "지갑 기준 증감 금액. 들어오면 +, 빠져나가면 -", example = "-12000")
        long amount,

        @Schema(description = "이 거래 후 지갑 잔액", example = "38000")
        long balanceAfter,

        @Schema(description = """
                referenceId가 가리키는 대상의 종류 (거래 유형에 따라 정해짐)
                - TOPUP: 충전 건 (CHARGE·CHARGE_CANCEL)
                - PAYMENT: 결제 건 (PAYMENT)
                - REFUND: 환불 건 (REFUND)
                - SETTLEMENT: 정산서 (SETTLEMENT·FEE)""", example = "PAYMENT")
        WalletTransactionReferenceType referenceType,

        @Schema(description = """
                이 거래를 만든 원본 건의 ID. referenceType이 TOPUP이면 충전 건 ID(GET /api/v1/topups/{topupId}의 topupId),
                PAYMENT면 결제 건 ID(주문 ID가 아님), REFUND면 환불 건 ID, SETTLEMENT면 정산서 ID.
                화면 표시용이 아니라 상세 화면으로 이동할 때 쓰는 값""", example = "1")
        Long referenceId,

        @Schema(description = "거래 시각", example = "2026-10-14T23:57:07")
        LocalDateTime createdAt
) {
    public static WalletTransactionDto from(WalletTransaction transaction) {
        return new WalletTransactionDto(transaction.getId(), transaction.getType(), transaction.getAmount(),
                transaction.getBalanceAfter(), transaction.getReferenceType(), transaction.getReferenceId(),
                transaction.getCreatedAt());
    }
}
