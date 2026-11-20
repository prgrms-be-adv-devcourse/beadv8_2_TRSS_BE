package com.backend.boundedContext.settlement.in.dto;

/**
 * 월 정산 수동 실행 접수 결과 (202)
 * Job은 별도 스레드에서 돈다. 결과(건수, 상태)는 실행 기록 조회(GET /api/v1/admin/settlements/runs)로 본다
 *
 * @param targetMonth 실행한 정산월(yyyy-MM)
 * @param status      STARTED(바로 시작) 또는 QUEUED(다른 달 수동 실행이 끝난 뒤 시작)
 */
public record SettlementRunStartResponse(String targetMonth, String status) {
}
