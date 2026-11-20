package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.SettlementJudgement;

/**
 * 판매자 1명 확정 결과
 *
 * @param candidateCount 정산서에 묶은 후보 수 (BLOCK, 이월이거나 대상이 없으면 0)
 * @param judgement      판별 결과 종류 (대상이 없으면 null). 확정 Step이 HOLD, BLOCK 건수를 센다
 */
public record SettlementConfirmResult(int candidateCount, SettlementJudgement.Type judgement) {

    static SettlementConfirmResult none() {
        return new SettlementConfirmResult(0, null);
    }

    static SettlementConfirmResult of(int candidateCount, SettlementJudgement.Type judgement) {
        return new SettlementConfirmResult(candidateCount, judgement);
    }
}
