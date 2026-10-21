package com.backend.boundedContext.settlement.in.dto;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementCandidate;

import java.util.List;

/** 관리자 정산서 상세: 금액·상태·최근 실패 사유·시도 횟수·다음 재시도 일시·보류 사유 + 묶인 후보 목록 */
public record SettlementDetailResponse(
        SettlementResponse settlement,
        List<SettlementCandidateResponse> candidates
) {
    public static SettlementDetailResponse of(Settlement settlement, String businessName,
                                              List<SettlementCandidate> candidates) {
        return new SettlementDetailResponse(SettlementResponse.of(settlement, businessName),
                candidates.stream().map(SettlementCandidateResponse::from).toList());
    }
}