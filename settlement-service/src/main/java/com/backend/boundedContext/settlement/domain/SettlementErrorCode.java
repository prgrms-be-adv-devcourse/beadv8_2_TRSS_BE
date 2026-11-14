package com.backend.boundedContext.settlement.domain;

import com.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 정산 컨텍스트 오류 코드
 * resultCode는 "{status}-{상수 이름}"으로 자동 생성
 * (예: 409-SETTLEMENT_STATE_INVALID)
 */
@Getter
@RequiredArgsConstructor
@Accessors(fluent = true)
public enum SettlementErrorCode implements ErrorCode {
    SETTLEMENT_NOT_FOUND(404, "정산서를 찾을 수 없습니다."),
    SETTLEMENT_RUNNING(409, "같은 월의 정산이 이미 실행 중입니다."),
    SETTLEMENT_STATE_INVALID(409, "현재 정산서 상태에서는 처리할 수 없습니다."),
    SETTLEMENT_SELLER_SANCTIONED(409, "제재 중인 판매자의 정산서는 보류를 해제할 수 없습니다."),
    SETTLEMENT_LEDGER_MISMATCH_UNRESOLVED(409, "원장 재대조 불일치가 풀리지 않은 판매자의 정산서는 보류를 해제할 수 없습니다."),
    SETTLEMENT_MONTH_INVALID(400, "아직 끝나지 않은 달은 정산할 수 없습니다."),

    SETTLEMENT_CANDIDATE_NOT_FOUND(404, "정산 후보를 찾을 수 없습니다."),
    SETTLEMENT_CANDIDATE_STATE_INVALID(409, "현재 정산 후보 상태에서는 처리할 수 없습니다."),

    SETTLEMENT_ALREADY_COMPLETED(409, "이미 정산이 완료된 달입니다."),
    SETTLEMENT_AMOUNT_MISMATCH(409, "정산 금액이 일치하지 않습니다."),
    SETTLEMENT_CANDIDATE_AMOUNT_INVALID(400, "주문 항목 금액이 올바르지 않아 정산 후보를 만들 수 없습니다."),
    SETTLEMENT_CANDIDATE_AMOUNT_TAMPERED(409, "정산 후보의 금액 스냅샷(판매가·수수료·지급액)이 서로 맞지 않습니다."),

    SETTLEMENT_LEDGER_MISSING(409, "지급된 정산서의 원장 기록을 찾을 수 없습니다."),
    SETTLEMENT_LEDGER_REPLACED(409, "지급된 정산서의 원장 행이 지급 당시와 다릅니다."),
    SETTLEMENT_LEDGER_AMOUNT_CHANGED(409, "지급된 정산서의 원장 금액이 지급 당시와 다릅니다."),
    SETTLEMENT_MONTH_RANGE_INVALID(400, "조회 기간이 올바르지 않습니다. 시작월은 끝월보다 늦을 수 없고, 정해진 개월 수까지만 볼 수 있습니다."),
    SETTLEMENT_EXPORT_TOO_LARGE(400, "내려받을 행이 너무 많습니다. 기간이나 조건을 줄여 주세요."),
    SETTLEMENT_EXPORT_FAILED(500, "엑셀 파일을 만들지 못했습니다. 잠시 후 다시 시도해 주세요."),
    SETTLEMENT_JOB_FAILED(500, "정산 작업을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.");

    private final int status;
    private final String msg;
}