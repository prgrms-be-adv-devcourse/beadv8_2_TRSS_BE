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
 * 관리자 보류 해제: HOLD 정산서를 READY로 되돌리고 처리 이력을 남긴다
 *
 * 지급은 여기서 하지 않는다. READY가 되면 10분마다 도는 재시도 스케줄러가 지급한다(정책 "보류").
 * 판매자 상태(제재 여부)는 다시 보지 않는다. 관리자가 확인하고 해제한 것으로 본다.
 * HOLD가 아니면 엔티티가 409 SETTLEMENT_STATE_INVALID를 던진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementReleaseUseCase {

    private final SettlementSupport settlementSupport;
    private final SettlementProcessHistoryRepository settlementProcessHistoryRepository;

    public SettlementResponse release(Long settlementId) {
        Settlement settlement = settlementSupport.getSettlementWithLock(settlementId);
        settlement.release();   // HOLD가 아니면 409
        settlementProcessHistoryRepository.save(SettlementProcessHistory.released(settlementId, LocalDateTime.now()));
        log.info("정산서 보류 해제 settlementId={} sellerId={}", settlementId, settlement.getSellerId());
        return SettlementResponse.from(settlement);
    }
}