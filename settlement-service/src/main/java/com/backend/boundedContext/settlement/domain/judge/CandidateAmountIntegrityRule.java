package com.backend.boundedContext.settlement.domain.judge;

import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import com.backend.boundedContext.settlement.domain.SettlementErrorCode;
import com.backend.boundedContext.settlement.domain.SettlementJudgement;
import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 원장 무결성 1단계 확인: 후보 금액 스냅샷이 서로 맞는지, 수수료율이 후보를 만든 시점의 정책 값과 같은지
 *
 * 후보 금액은 updatable=false라 코드로는 바뀌지 않지만, DB에 직접 손댄 경우를 여기서 잡는다.
 *   ① 금액 일관성: 판매가 > 0, 수수료 = 판매가 × 수수료율 ÷ 100(버림), 지급액 = 판매가 − 수수료
 *   ② 수수료율: 후보의 수수료율 = 후보를 만든 시점의 정책 수수료율(SettlementPolicy.feeRateAt)
 * ①만 보면 "수수료율 0, 수수료 0, 지급액 = 판매가"처럼 세 컬럼을 함께 바꾼 경우를 통과시킨다. ②가 그 경우를 잡는다.
 * 하나라도 어긋나면 그 판매자는 이번 실행에서 확정하지 않는다(BLOCK, 사람 확인)
 */
@Component
public class CandidateAmountIntegrityRule implements SettlementJudgeRule {

    /** null이면 ②를 건너뛴다(정책 없이 만든 단위 테스트용) */
    private final SettlementPolicy settlementPolicy;

    /** 정책 없이 ①만 확인한다(단위 테스트용) */
    public CandidateAmountIntegrityRule() {
        this(null);
    }

    @Autowired
    public CandidateAmountIntegrityRule(SettlementPolicy settlementPolicy) {
        this.settlementPolicy = settlementPolicy;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public SettlementJudgement judge(SettlementJudgeContext context) {
        List<Long> tampered = new ArrayList<>();
        List<Long> feeRateMismatched = new ArrayList<>();
        for (SettlementCandidate candidate : context.candidates()) {
            if (!candidate.hasConsistentAmounts()) tampered.add(candidate.getId());
            else if (!hasPolicyFeeRate(candidate)) feeRateMismatched.add(candidate.getId());
        }
        if (tampered.isEmpty() && feeRateMismatched.isEmpty()) return SettlementJudgement.pass();

        List<Long> rejected = new ArrayList<>(tampered);
        rejected.addAll(feeRateMismatched);
        SettlementErrorCode code = tampered.isEmpty()
                ? SettlementErrorCode.SETTLEMENT_CANDIDATE_FEE_RATE_MISMATCH
                : SettlementErrorCode.SETTLEMENT_CANDIDATE_AMOUNT_TAMPERED;
        return SettlementJudgement.block(code.resultCode(),
                "후보 금액 스냅샷 불일치 %d건 %s, 수수료율 불일치 %d건 %s".formatted(tampered.size(), tampered,
                        feeRateMismatched.size(), feeRateMismatched), rejected);
    }

    /** 후보를 만든 시점(저장 시각, 없으면 구매 확정 시각)의 정책 수수료율과 같은지 */
    private boolean hasPolicyFeeRate(SettlementCandidate candidate) {
        if (settlementPolicy == null) return true;
        LocalDateTime createdAt = candidate.getCreatedAt() != null ? candidate.getCreatedAt() : candidate.getConfirmedAt();
        return candidate.getFeeRate() == settlementPolicy.feeRateAt(createdAt);
    }
}
