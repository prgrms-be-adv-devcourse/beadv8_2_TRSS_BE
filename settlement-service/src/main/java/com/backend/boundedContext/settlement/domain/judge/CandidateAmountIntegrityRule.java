package com.backend.boundedContext.settlement.domain.judge;

import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import com.backend.boundedContext.settlement.domain.SettlementErrorCode;
import com.backend.boundedContext.settlement.domain.SettlementJudgement;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 원장 무결성 1단계 확인: 후보 금액 스냅샷이 서로 맞는지
 *
 * 후보 금액은 updatable=false라 코드로는 바뀌지 않지만, DB에 직접 손댄 경우를 여기서 잡는다.
 * 판매가 > 0, 수수료 = 판매가 × 수수료율 ÷ 100(버림), 지급액 = 판매가 − 수수료
 * 하나라도 어긋나면 그 판매자는 이번 실행에서 확정하지 않는다(BLOCK, 사람 확인)
 */
@Component
public class CandidateAmountIntegrityRule implements SettlementJudgeRule {

    @Override
    public int order() {
        return 10;
    }

    @Override
    public SettlementJudgement judge(SettlementJudgeContext context) {
        List<Long> broken = context.candidates().stream()
                .filter(candidate -> !candidate.hasConsistentAmounts())
                .map(SettlementCandidate::getId)
                .toList();
        if (broken.isEmpty()) return SettlementJudgement.pass();
        return SettlementJudgement.block(SettlementErrorCode.SETTLEMENT_CANDIDATE_AMOUNT_TAMPERED.resultCode(),
                "후보 %d건의 금액 스냅샷이 맞지 않음 candidateIds=%s".formatted(broken.size(), broken), broken);
    }
}
