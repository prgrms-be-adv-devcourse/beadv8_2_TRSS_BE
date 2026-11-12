package com.backend.boundedContext.settlement.in.dto;

import java.time.LocalDateTime;

/**
 * 관리자가 월 정산을 수동으로 실행할 수 있는 달 1개 (수동 실행 확인 창의 "실행할 달" 목록)
 *
 * @param settlementMonth 정산월 yyyy-MM
 * @param reason          FAILED: 마지막 실행이 실패·중단돼 이어서 실행할 수 있음 / NOT_RUN: 자동 실행 시각이 지났는데 실행 기록이 없음
 * @param lastStatus      마지막 실행 상태(Spring Batch BatchStatus). NOT_RUN이면 null
 * @param lastRunAt       마지막 실행 시작 시각. NOT_RUN이면 null
 */
public record SettlementRunnableMonthResponse(
        String settlementMonth,
        String reason,
        String lastStatus,
        LocalDateTime lastRunAt
) {
}
