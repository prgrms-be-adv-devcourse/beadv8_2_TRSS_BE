package com.backend.boundedContext.payment.domain;

// 대사가 자동으로 정할 수 없어 수동 확인으로 표시한 사유
public enum TopupManualCheckReason {

    CANCEL_AMOUNT_MISMATCH,         // CANCELING: PG 취소 합계가 이전 취소와도, 이전 + 이번 취소와도 다름 (상점관리자 직접 취소 등)
    PG_PAYMENT_NOT_FOUND,           // CANCELING·METHOD_CANCELING: PG에 결제가 없음
    METHOD_CANCEL_PARTIAL,          // METHOD_CANCELING: 일부만 취소돼 전액 취소를 다시 보낼 수 없음
    METHOD_CANCEL_RETRY_EXHAUSTED   // METHOD_CANCELING: PG 전액 취소를 최대 횟수만큼 시도했지만 취소되지 않음
}
