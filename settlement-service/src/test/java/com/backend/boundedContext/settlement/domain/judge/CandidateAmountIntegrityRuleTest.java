package com.backend.boundedContext.settlement.domain.judge;

import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import com.backend.boundedContext.settlement.domain.SettlementJudgement;
import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 판별 규칙: 후보 금액 스냅샷 일관성 + 수수료율이 후보를 만든 시점의 정책 값과 같은지
 */
class CandidateAmountIntegrityRuleTest {

    private static final LocalDateTime CONFIRMED_AT = LocalDateTime.of(2026, 9, 15, 12, 0);

    private final CandidateAmountIntegrityRule rule =
            new CandidateAmountIntegrityRule(new SettlementPolicy(10, 10, 3, 10, 100, 50));

    @Test
    @DisplayName("금액이 맞고 수수료율이 정책 값(10%)이면 PASS")
    void consistentAndPolicyRate_pass() {
        assertTrue(rule.judge(context(candidate(1L, 20_000L, 10))).isPass());
    }

    @Test
    @DisplayName("세 컬럼을 서로 맞게 함께 바꾼 경우(수수료율 0, 수수료 0, 지급액 = 판매가)는 수수료율 확인이 잡는다")
    void consistentButZeroRate_blockedByFeeRate() {
        SettlementJudgement judgement = rule.judge(context(candidate(1L, 20_000L, 10), candidate(2L, 20_000L, 0)));

        assertEquals(SettlementJudgement.Type.BLOCK, judgement.type());
        assertEquals("409-SETTLEMENT_CANDIDATE_FEE_RATE_MISMATCH", judgement.errorCode());
        assertEquals(List.of(2L), judgement.rejectedCandidateIds());
    }

    @Test
    @DisplayName("금액 불일치와 수수료율 불일치가 섞이면 금액 불일치 코드로 막고 두 후보를 모두 돌려준다")
    void tamperedAndRateMismatch_blockedAsTampered() {
        SettlementCandidate tampered = candidate(1L, 20_000L, 10);
        ReflectionTestUtils.setField(tampered, "payoutAmount", 99_000L);

        SettlementJudgement judgement = rule.judge(context(tampered, candidate(2L, 20_000L, 0)));

        assertEquals("409-SETTLEMENT_CANDIDATE_AMOUNT_TAMPERED", judgement.errorCode());
        assertEquals(List.of(1L, 2L), judgement.rejectedCandidateIds());
    }

    @Test
    @DisplayName("정책 없이 만든 규칙(단위 테스트용)은 금액 일관성만 본다")
    void withoutPolicy_checksAmountsOnly() {
        CandidateAmountIntegrityRule amountsOnly = new CandidateAmountIntegrityRule();

        assertTrue(amountsOnly.judge(context(candidate(2L, 20_000L, 0))).isPass());
    }

    private static SettlementJudgeContext context(SettlementCandidate... candidates) {
        return new SettlementJudgeContext(7L, List.of(candidates), null, false);
    }

    private static SettlementCandidate candidate(Long id, long salePrice, int feeRate) {
        SettlementCandidate candidate = SettlementCandidate.create(100L + id, 1000L + id, 7L, salePrice, feeRate,
                CONFIRMED_AT);
        ReflectionTestUtils.setField(candidate, "id", id);
        return candidate;
    }
}
