package com.backend.boundedContext.settlement.domain.judge;

import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import com.backend.shared.member.dto.SellerStatus;

import java.util.List;

/**
 * 판별 규칙이 보는 판매자 1명의 상황
 *
 * @param sellerId        판매자 ID
 * @param candidates      이번 실행에 묶일 READY 후보(정산월 ≤ 대상월). 관리자 제외(EXCLUDED)는 조회 단계에서 이미 빠져 있다
 * @param sellerStatus    판매자 상태. 판매자 행이 없으면 null(지급 단계에서 SELLER_NOT_FOUND로 수동 처리)
 * @param ledgerMismatch  이 판매자의 지난 정산서에 아직 풀리지 않은 원장 재대조 불일치가 있는지
 */
public record SettlementJudgeContext(Long sellerId, List<SettlementCandidate> candidates, SellerStatus sellerStatus,
                                     boolean ledgerMismatch) {

    public SettlementJudgeContext {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
}
