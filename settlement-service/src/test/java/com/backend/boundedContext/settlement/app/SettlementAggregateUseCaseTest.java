package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import com.backend.boundedContext.settlement.domain.SettlementCandidateStatus;
import com.backend.boundedContext.settlement.domain.SettlementHistoryResult;
import com.backend.boundedContext.settlement.domain.SettlementHistoryStage;
import com.backend.boundedContext.settlement.domain.SettlementHistoryTargetType;
import com.backend.boundedContext.settlement.domain.SettlementHoldType;
import com.backend.boundedContext.settlement.domain.SettlementJudgement;
import com.backend.boundedContext.settlement.domain.SettlementProcessHistory;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.domain.judge.CandidateAmountIntegrityRule;
import com.backend.boundedContext.settlement.domain.judge.LedgerMismatchRule;
import com.backend.boundedContext.settlement.domain.judge.SellerStatusRule;
import com.backend.boundedContext.settlement.domain.judge.SettlementJudgeChain;
import com.backend.boundedContext.settlement.out.SettlementCandidateRepository;
import com.backend.boundedContext.settlement.out.SettlementProcessHistoryRepository;
import com.backend.boundedContext.settlement.out.SettlementRepository;
import com.backend.shared.member.dto.SellerStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 확정 Step의 판매자 1명 확정: 잠근 후보로 판별 규칙 체인을 실행한 뒤 정산서를 만든다
 * Repository는 JDK 동적 프록시로 쓰는 메서드만 흉내 낸다. 규칙 체인은 실제 규칙 3개를 쓴다
 */
class SettlementAggregateUseCaseTest {

    private static final Long SELLER_ID = 7L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 0, 0);
    private static final LocalDate PAYABLE_ON = LocalDate.of(2026, 10, 12);

    private final List<SettlementProcessHistory> histories = new ArrayList<>();
    private final List<Settlement> saved = new ArrayList<>();
    private List<SettlementCandidate> readyCandidates = List.of();

    @Test
    @DisplayName("잠근 후보의 금액 스냅샷이 어긋나면 BLOCK: 정산서를 만들지 않고 후보 막힘, 판매자 판정, 확정 실패 이력을 남긴다")
    void tamperedCandidate_blocked() {
        SettlementCandidate ok = candidate(1L, 10_000L);
        SettlementCandidate tampered = candidate(2L, 10_000L);
        ReflectionTestUtils.setField(tampered, "payoutAmount", 99_000L);   // DB에서 지급액을 직접 고친 경우
        readyCandidates = List.of(ok, tampered);

        SettlementConfirmResult result = confirm(SellerStatus.APPROVED, false);

        assertEquals(0, result.candidateCount());
        assertEquals(SettlementJudgement.Type.BLOCK, result.judgement());
        assertTrue(saved.isEmpty());
        assertEquals(SettlementCandidateStatus.READY, tampered.getStatus());   // 후보는 다음 실행에서 다시 본다
        SettlementProcessHistory blocked = histories.get(0);
        assertEquals(SettlementHistoryTargetType.CANDIDATE, blocked.getTargetType());
        assertEquals(2L, blocked.getTargetId());
        assertEquals("409-SETTLEMENT_CANDIDATE_AMOUNT_TAMPERED", blocked.getErrorCode());
        SettlementProcessHistory last = histories.get(histories.size() - 1);
        assertEquals(SettlementHistoryStage.AGGREGATE, last.getStage());
        assertEquals(SettlementHistoryResult.FAILED, last.getResult());
    }

    @Test
    @DisplayName("PASS면 READY 정산서를 만들고 지급 예정일을 정한다")
    void pass_readySettlementWithPayableDate() {
        readyCandidates = List.of(candidate(1L, 10_000L), candidate(2L, 15_000L));

        SettlementConfirmResult result = confirm(SellerStatus.APPROVED, false);

        assertEquals(2, result.candidateCount());
        assertEquals(SettlementJudgement.Type.PASS, result.judgement());
        assertEquals(1, saved.size());
        assertEquals(SettlementStatus.READY, saved.get(0).getStatus());
        assertEquals(PAYABLE_ON, saved.get(0).getPayableOn());
    }

    @Test
    @DisplayName("판매자가 제재 중이면 HOLD(제재) 정산서를 만든다")
    void sanctionedSeller_holdSanction() {
        readyCandidates = List.of(candidate(1L, 10_000L));

        SettlementConfirmResult result = confirm(SellerStatus.SANCTIONED, false);

        assertEquals(1, result.candidateCount());
        assertEquals(SettlementJudgement.Type.HOLD, result.judgement());
        assertEquals(SettlementStatus.HOLD, saved.get(0).getStatus());
        assertEquals(SettlementHoldType.SANCTION, saved.get(0).getHoldType());
    }

    @Test
    @DisplayName("원장 불일치가 남은 판매자면 HOLD(원장 불일치) 정산서를 만든다")
    void ledgerMismatch_holdLedger() {
        readyCandidates = List.of(candidate(1L, 10_000L));

        SettlementConfirmResult result = confirm(SellerStatus.APPROVED, true);

        assertEquals(SettlementJudgement.Type.HOLD, result.judgement());
        assertEquals(SettlementHoldType.LEDGER_MISMATCH, saved.get(0).getHoldType());
    }

    @Test
    @DisplayName("READY 후보가 없으면 아무것도 하지 않는다(판정 없음, 이력 없음)")
    void noCandidates_nothing() {
        readyCandidates = List.of();

        SettlementConfirmResult result = confirm(SellerStatus.APPROVED, false);

        assertEquals(0, result.candidateCount());
        assertNull(result.judgement());
        assertTrue(histories.isEmpty());
    }

    private SettlementConfirmResult confirm(SellerStatus status, boolean ledgerMismatch) {
        return useCase().confirmSeller(SELLER_ID, YearMonth.of(2026, 9), 9L, SettlementTrigger.MONTHLY_JOB, status,
                ledgerMismatch, PAYABLE_ON);
    }

    private SettlementAggregateUseCase useCase() {
        SettlementRepository settlementRepository = fake(SettlementRepository.class, Map.of(
                "findWithLockBySellerIdAndSettlementMonth", args -> Optional.empty(),
                "save", args -> {
                    Settlement s = (Settlement) args[0];
                    ReflectionTestUtils.setField(s, "id", 31L);
                    saved.add(s);
                    return s;
                }));
        SettlementCandidateRepository candidateRepository = fake(SettlementCandidateRepository.class, Map.of(
                "findWithLockReadyBySellerIdUntilMonth", args -> readyCandidates));
        SettlementProcessHistoryRepository historyRepository = fake(SettlementProcessHistoryRepository.class, Map.of(
                "save", args -> {
                    histories.add((SettlementProcessHistory) args[0]);
                    return args[0];
                }));
        SettlementJudgeChain chain = new SettlementJudgeChain(List.of(
                new CandidateAmountIntegrityRule(), new LedgerMismatchRule(), new SellerStatusRule()));
        return new SettlementAggregateUseCase(settlementRepository, candidateRepository, historyRepository, chain);
    }

    private static SettlementCandidate candidate(Long id, long salePrice) {
        SettlementCandidate candidate = SettlementCandidate.create(100L + id, 1000L + id, SELLER_ID, salePrice, 10,
                NOW.minusDays(20));
        ReflectionTestUtils.setField(candidate, "id", id);
        return candidate;
    }

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type, Map<String, Function<Object[], Object>> handlers) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            Function<Object[], Object> handler = handlers.get(method.getName());
            if (handler != null) return handler.apply(args);
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> type.getSimpleName() + "Fake";
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
    }
}
