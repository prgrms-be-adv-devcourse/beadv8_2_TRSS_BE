package com.backend.boundedContext.settlement.domain;

import com.backend.boundedContext.settlement.domain.judge.CandidateAmountIntegrityRule;
import com.backend.boundedContext.settlement.domain.judge.LedgerMismatchRule;
import com.backend.boundedContext.settlement.domain.judge.SellerStatusRule;
import com.backend.boundedContext.settlement.domain.judge.SettlementJudgeChain;
import com.backend.boundedContext.settlement.domain.judge.SettlementJudgeContext;
import com.backend.shared.member.dto.SellerStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 지급 직전 판별: 규칙 체인과 판정 문자열 변환
 */
class SettlementJudgementTest {

    private static final LocalDateTime CONFIRMED_AT = LocalDateTime.of(2026, 9, 10, 12, 0);

    // 등록 순서를 섞어도 order()대로 돈다
    private final SettlementJudgeChain chain = new SettlementJudgeChain(List.of(
            new SellerStatusRule(), new LedgerMismatchRule(), new CandidateAmountIntegrityRule()));

    @Test
    @DisplayName("문제가 없으면 PASS")
    void pass() {
        SettlementJudgement judgement = chain.judge(context(List.of(candidate(10_000L)), SellerStatus.APPROVED, false));
        assertEquals(SettlementJudgement.Type.PASS, judgement.type());
    }

    @Test
    @DisplayName("관리자 제재(SANCTIONED)만 HOLD, 철회 완료(WITHDRAWN)는 기존처럼 그대로 진행")
    void sellerStatusHold() {
        assertEquals(SellerStatusRule.SANCTIONED_REASON,
                chain.judge(context(List.of(candidate(10_000L)), SellerStatus.SANCTIONED, false)).reason());
        assertEquals(SettlementJudgement.Type.PASS,
                chain.judge(context(List.of(candidate(10_000L)), SellerStatus.WITHDRAWN, false)).type());
    }

    @Test
    @DisplayName("판매자 행이 없으면(null) 판별은 PASS하고 지급 단계에 맡긴다")
    void unknownSellerPasses() {
        assertEquals(SettlementJudgement.Type.PASS,
                chain.judge(context(List.of(candidate(10_000L)), null, false)).type());
    }

    @Test
    @DisplayName("풀리지 않은 원장 재대조 불일치가 있으면 HOLD")
    void ledgerMismatchHold() {
        SettlementJudgement judgement = chain.judge(context(List.of(candidate(10_000L)), SellerStatus.APPROVED, true));
        assertEquals(SettlementJudgement.Type.HOLD, judgement.type());
    }

    @Test
    @DisplayName("후보 금액 스냅샷이 맞지 않으면 BLOCK이고, 다른 HOLD 조건보다 먼저 나온다")
    void tamperedCandidateBlocksFirst() throws Exception {
        SettlementCandidate ok = candidate(10_000L);
        SettlementCandidate tampered = candidate(20_000L);
        setField(tampered, "id", 42L);
        setField(tampered, "payoutAmount", 19_999L);   // DB에서 직접 바꾼 상황

        SettlementJudgement judgement = chain.judge(context(List.of(ok, tampered), SellerStatus.SANCTIONED, true));

        assertEquals(SettlementJudgement.Type.BLOCK, judgement.type());
        assertEquals("409-SETTLEMENT_CANDIDATE_AMOUNT_TAMPERED", judgement.errorCode());
        assertEquals(List.of(42L), judgement.rejectedCandidateIds());
    }

    @Test
    @DisplayName("판정은 Job 실행 컨텍스트에 문자열로 저장했다가 그대로 되돌린다")
    void encodeDecode() {
        SettlementJudgement hold = SettlementJudgement.decode(SettlementJudgement.hold("사유::포함").encode());
        assertEquals(SettlementJudgement.Type.HOLD, hold.type());
        assertNull(hold.errorCode());
        assertEquals("사유::포함", hold.reason());

        SettlementJudgement block = SettlementJudgement.decode(
                SettlementJudgement.block("409-X", "금액", List.of(1L)).encode());
        assertEquals(SettlementJudgement.Type.BLOCK, block.type());
        assertEquals("409-X", block.errorCode());
        assertTrue(block.rejectedCandidateIds().isEmpty());   // 후보 ID는 처리 이력에 남기므로 싣지 않는다

        assertTrue(SettlementJudgement.decode(SettlementJudgement.pass().encode()).isPass());
    }

    private static SettlementJudgeContext context(List<SettlementCandidate> candidates, SellerStatus status,
                                                  boolean ledgerMismatch) {
        return new SettlementJudgeContext(7L, candidates, status, ledgerMismatch);
    }

    private static SettlementCandidate candidate(long salePrice) {
        return SettlementCandidate.create(100L, salePrice, 7L, salePrice, 10, CONFIRMED_AT);
    }

    static void setField(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
