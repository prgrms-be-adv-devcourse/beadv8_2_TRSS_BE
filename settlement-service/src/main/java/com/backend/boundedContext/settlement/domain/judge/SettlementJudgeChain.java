package com.backend.boundedContext.settlement.domain.judge;

import com.backend.boundedContext.settlement.domain.SettlementJudgement;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 판별 규칙 체인. 처음으로 PASS가 아닌 규칙의 결과를 돌려준다
 */
@Component
public class SettlementJudgeChain {

    private final List<SettlementJudgeRule> rules;

    public SettlementJudgeChain(List<SettlementJudgeRule> rules) {
        this.rules = rules.stream().sorted(Comparator.comparingInt(SettlementJudgeRule::order)).toList();
    }

    public SettlementJudgement judge(SettlementJudgeContext context) {
        for (SettlementJudgeRule rule : rules) {
            SettlementJudgement judgement = rule.judge(context);
            if (!judgement.isPass()) return judgement;
        }
        return SettlementJudgement.pass();
    }
}
