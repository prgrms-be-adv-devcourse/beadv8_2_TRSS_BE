package com.backend.boundedContext.payment.domain;

import com.backend.global.exception.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@RequiredArgsConstructor
@Accessors(fluent = true)
public enum PaymentErrorCode implements ErrorCode {

    // 지갑
    WALLET_NOT_FOUND(404, "지갑을 찾을 수 없습니다."),
    INSUFFICIENT_BALANCE(402, "예치금 잔액이 부족합니다."),

    // 조회 (내 거래 내역·관리자 결제 조회 공통)
    PAYMENT_PERIOD_INVALID(400, "조회 시작일은 종료일보다 늦을 수 없습니다."),

    // 충전
    TOPUP_NOT_FOUND(404, "충전 내역을 찾을 수 없습니다."),
    TOPUP_AMOUNT_INVALID(400, "충전 금액은 10,000원 단위로 10,000원 이상 1,000,000원 이하만 가능합니다."),
    AMOUNT_MISMATCH(400, "충전 요청 금액이 일치하지 않습니다."),
    TOPUP_PAYMENT_DECLINED(400, "결제가 승인되지 않았습니다."),
    TOPUP_ALREADY_PROCESSED(409, "이미 처리된 충전입니다."),
    TOPUP_NOT_CANCELABLE(409, "취소할 수 있는 충전 금액이 없습니다."),
    TOPUP_CANCEL_DECLINED(400, "충전 취소가 거절되었습니다."),
    TOPUP_METHOD_NOT_ALLOWED(400, "충전에 사용할 수 없는 결제 수단입니다. 결제는 자동으로 취소됩니다."),
    TOPUP_PG_UNAVAILABLE(503, "결제사 장애로 지금은 충전할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    TOPUP_NOT_MANUAL_CHECK(409, "수동 확인 대상인 충전이 아닙니다."),
    TOPUP_MANUAL_RESOLVE_NOT_ALLOWED(409, "지금 충전 상태에서는 할 수 없는 정리입니다."),

    // 결제
    PAYMENT_ALREADY_PAID(409, "이미 결제된 주문입니다."),
    PAYMENT_CANCELED(409, "취소된 결제입니다."),
    PAYMENT_NOT_CANCELABLE(409, "취소할 수 없는 결제입니다."),
    PLATFORM_SUBSIDY_INSUFFICIENT(409, "플랫폼 지갑 잔액이 쿠폰 지원금보다 적어 결제할 수 없습니다. 관리자에게 문의해 주세요."),

    // 반품 환불 (이벤트로 처리하므로 응답으로 나가지 않고, 환불 기록의 FAILED_사유로 남는다)
    REFUND_HOLDING_SETTLED(409, "정산이 시작된 홀딩이라 환불할 수 없습니다."),
    REFUND_NOT_FOUND(404, "환불 내역을 찾을 수 없습니다."),
    REFUND_PICKUP_FEE_NOT_REQUIRED(409, "반품 회수 택배비를 결제할 수 있는 환불이 아닙니다."),
    REFUND_PLATFORM_BALANCE_INSUFFICIENT(409, "플랫폼 지갑 잔액이 모자라 처음 배송비를 돌려줄 수 없습니다."),

    // 홀딩·정산 지급
    HOLDING_NOT_FOUND(404, "보관 중인 홀딩 내역을 찾을 수 없습니다."),
    HOLDING_INSUFFICIENT(409, "홀딩의 남은 금액이 지급액보다 적습니다."),
    TRANSFER_AMOUNT_MISMATCH(409, "주문별 지급 금액 합계가 지급액과 수수료의 합과 다릅니다.");

    private final int status;
    private final String msg;
}
