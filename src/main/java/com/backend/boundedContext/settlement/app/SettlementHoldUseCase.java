package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementProcessHistory;
import com.backend.boundedContext.settlement.in.dto.SettlementResponse;
import com.backend.boundedContext.settlement.out.SettlementProcessHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 관리자 정산서 보류: 지급 전 정산서(READY·FAILED·MANUAL_REQUIRED) 1건을 HOLD로 바꾸고 보류 사유·처리 이력을 남긴다
 *
 * 정산서를 잠근 뒤 바꾸므로, 지급 트랜잭션이 잡고 있으면 지급이 끝날 때까지 기다린다.
 * 그 사이 PAID가 됐거나 이미 HOLD면 엔티티가 409 SETTLEMENT_STATE_INVALID를 던진다.
 * HOLD 동안에는 월 정산 Job·재시도 스케줄러·수동 재처리 어느 경로로도 지급하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementHoldUseCase {

    private final SettlementSupport settlementSupport;
    private final SettlementProcessHistoryRepository settlementProcessHistoryRepository;

    public SettlementResponse hold(Long settlementId, String reason) {
        String strippedReason = reason.strip();
        Settlement settlement = settlementSupport.getSettlementWithLock(settlementId);
        settlement.hold(strippedReason);   // 지급 전 상태가 아니면 409
        settlementProcessHistoryRepository.save(
                SettlementProcessHistory.held(settlementId, strippedReason, LocalDateTime.now()));
        log.info("정산서 보류 settlementId={} sellerId={}", settlementId, settlement.getSellerId());
        return SettlementResponse.of(settlement, settlementSupport.findBusinessName(settlement.getSellerId()));
    }
}