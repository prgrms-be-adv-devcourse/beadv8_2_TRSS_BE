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
    SETTLEMENT_MONTH_INVALID(400, "아직 끝나지 않은 달은 정산할 수 없습니다."),

    SETTLEMENT_CANDIDATE_NOT_FOUND(404, "정산 후보를 찾을 수 없습니다."),
    SETTLEMENT_CANDIDATE_STATE_INVALID(409, "현재 정산 후보 상태에서는 처리할 수 없습니다.");

    private final int status;
    private final String msg;
}