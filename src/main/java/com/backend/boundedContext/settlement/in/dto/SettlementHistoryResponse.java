package com.backend.boundedContext.settlement.in.dto;

import com.backend.boundedContext.settlement.domain.SettlementHistoryResult;
import com.backend.boundedContext.settlement.domain.SettlementHistoryStage;
import com.backend.boundedContext.settlement.domain.SettlementProcessHistory;
import com.backend.boundedContext.settlement.domain.SettlementTrigger;

import java.time.LocalDateTime;

/** 관리자 정산 처리 이력 1건
 *
 * 집계·지급·보류·해제의 성공·실패·건너뜀 */
public record SettlementHistoryResponse(
        Long historyId,
        SettlementHistoryStage stage,
        int attemptNo,
        SettlementTrigger trigger,
        SettlementHistoryResult result,
        String errorCode,
        String failReason,
        String adminReason,
        Long batchExecutionId,
        LocalDateTime processedAt
) {
    public static SettlementHistoryResponse from(SettlementProcessHistory h) {
        return new SettlementHistoryResponse(h.getId(), h.getStage(), h.getAttemptNo(), h.getTriggerType(),
                h.getResult(), h.getErrorCode(), h.getFailReason(), h.getAdminReason(), h.getBatchExecutionId(),
                h.getProcessedAt());
    }
}