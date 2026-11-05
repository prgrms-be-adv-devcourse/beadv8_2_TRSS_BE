package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.in.dto.SettlementDetailResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 관리자 정산서 상세 (금액·상태·실패 사유·보류 사유 + 묶인 후보 목록) + 판매자 상호명
 * 없는 정산서는 404 SETTLEMENT_NOT_FOUND다
 */
@Component
@RequiredArgsConstructor
public class SettlementGetSettlementDetailUseCase {

    private final SettlementSupport settlementSupport;

    public SettlementDetailResponse getSettlement(Long settlementId) {
        Settlement settlement = settlementSupport.getSettlement(settlementId);
        return SettlementDetailResponse.of(settlement, settlementSupport.findBusinessName(settlement.getSellerId()),
                settlementSupport.findCandidatesOf(settlementId));
    }
}