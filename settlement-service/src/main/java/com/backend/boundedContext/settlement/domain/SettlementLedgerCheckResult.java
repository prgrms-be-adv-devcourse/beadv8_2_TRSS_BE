package com.backend.boundedContext.settlement.domain;

/**
 * 원장 재대조 결과 (지급 영수증 = 정산서의 원장 행 ID·지급액·수수료 vs 결제 원장을 다시 읽은 값)
 *
 * 원장(wallet_transaction)은 결제 모듈 소유라 정산이 수정을 막을 수 없다.
 * 대신 다시 읽어 지급 당시와 같은지 비교해 바뀐 것을 찾아낸다(원장 무결성 2단계 ②).
 */
public enum SettlementLedgerCheckResult {
    /** 같음 */
    OK(null),
    /** 원장에 지급 기록이 없음(행 삭제) */
    MISSING(SettlementErrorCode.SETTLEMENT_LEDGER_MISSING),
    /** 원장 행 ID가 지급 당시와 다름(행 교체) */
    REPLACED(SettlementErrorCode.SETTLEMENT_LEDGER_REPLACED),
    /** 지급액·수수료가 지급 당시와 다름(금액 변경) */
    AMOUNT_CHANGED(SettlementErrorCode.SETTLEMENT_LEDGER_AMOUNT_CHANGED);

    private final SettlementErrorCode errorCode;

    SettlementLedgerCheckResult(SettlementErrorCode errorCode) {
        this.errorCode = errorCode;
    }

    public boolean isMismatch() {
        return this != OK;
    }

    /** 처리 이력 error_code에 남길 resultCode (OK면 null) */
    public String resultCode() {
        return errorCode == null ? null : errorCode.resultCode();
    }

    public String message() {
        return errorCode == null ? null : errorCode.msg();
    }
}
