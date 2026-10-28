package com.backend.boundedContext.settlement.in.dto;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementCandidate;

import java.util.List;

/** 판매자 본인 정산서 상세: 판매 대금·수수료·지급액(목록 1행과 같은 값) + 품목별 내역 */
public record SellerSettlementDetailResponse(
        SellerSettlementResponse settlement,
        List<SellerSettlementItemResponse> items
) {
    public static SellerSettlementDetailResponse of(Settlement settlement, List<SettlementCandidate> candidates) {
        return new SellerSettlementDetailResponse(SellerSettlementResponse.from(settlement),
                candidates.stream()
                        .map(c -> SellerSettlementItemResponse.of(c, settlement.getSettlementMonth()))
                        .toList());
    }
}