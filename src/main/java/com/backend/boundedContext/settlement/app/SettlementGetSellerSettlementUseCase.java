package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementErrorCode;
import com.backend.boundedContext.settlement.in.dto.SellerSettlementDetailResponse;
import com.backend.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 판매자 본인 정산서 상세 (판매 대금·수수료·지급액 + 품목별 내역, 이월된 품목은 원래 정산월 표시)
 * 소유자(sellerId)를 조회 조건에 넣으므로 남의 정산서는 404 SETTLEMENT_NOT_FOUND다
 */
@Component
@RequiredArgsConstructor
public class SettlementGetSellerSettlementUseCase {

    private final SettlementSupport settlementSupport;

    public SellerSettlementDetailResponse getSellerSettlement(Long memberId, Long settlementId) {
        Long sellerId = settlementSupport.findSellerId(memberId)
                .orElseThrow(() -> new DomainException(SettlementErrorCode.SETTLEMENT_NOT_FOUND));
        Settlement settlement = settlementSupport.getSellerSettlement(settlementId, sellerId);
        return SellerSettlementDetailResponse.of(settlement, settlementSupport.findCandidatesOf(settlementId));
    }
}