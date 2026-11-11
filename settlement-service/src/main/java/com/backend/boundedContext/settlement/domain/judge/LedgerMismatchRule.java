package com.backend.boundedContext.settlement.domain.judge;

import com.backend.boundedContext.settlement.domain.SettlementJudgement;
import org.springframework.stereotype.Component;

/**
 * 원장 무결성 2단계 결과 반영: 이 판매자의 지난 지급 원장이 재대조에서 불일치로 남아 있으면 이번 지급을 보류한다
 * 불일치가 풀리면(재대조가 다시 OK) 다음 실행부터 자동으로 PASS하고, 이 사유로 보류된 정산서도 자동 해제한다(SettlementLedgerRecheckUseCase)
 */
@Component
public class LedgerMismatchRule implements SettlementJudgeRule {

    /** 원장 불일치로 보류한 정산서의 보류 사유. 불일치가 풀리면 이 사유로 보류된 정산서만 자동 해제한다 */
    public static final String REASON = "지난 정산 지급 원장 재대조 불일치가 풀리지 않아 지급을 보류합니다.";

    @Override
    public int order() {
        return 20;
    }

    @Override
    public SettlementJudgement judge(SettlementJudgeContext context) {
        return context.ledgerMismatch() ? SettlementJudgement.hold(REASON) : SettlementJudgement.pass();
    }
}
