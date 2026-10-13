package com.backend.boundedContext.settlement.out;

/**
 * 정산서에 묶인(INCLUDED) 후보 합계
 * 지급 전 "정산서 합계 = 후보 합계" 확인에 쓴다
 * 후보가 없으면 coalesce로 0이 들어온다
 */
public record SettlementCandidateTotalsRow(Long saleTotal, Long feeTotal, Long payoutTotal, Long count) {
}