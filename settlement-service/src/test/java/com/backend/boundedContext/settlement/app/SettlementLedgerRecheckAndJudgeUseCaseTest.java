package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import com.backend.boundedContext.settlement.domain.SettlementHistoryResult;
import com.backend.boundedContext.settlement.domain.SettlementHistoryStage;
import com.backend.boundedContext.settlement.domain.SettlementHistoryTargetType;
import com.backend.boundedContext.settlement.domain.SettlementJudgement;
import com.backend.boundedContext.settlement.domain.SettlementLedgerCheckResult;
import com.backend.boundedContext.settlement.domain.SettlementProcessHistory;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.domain.judge.CandidateAmountIntegrityRule;
import com.backend.boundedContext.settlement.domain.judge.LedgerMismatchRule;
import com.backend.boundedContext.settlement.domain.judge.SellerStatusRule;
import com.backend.boundedContext.settlement.domain.judge.SettlementJudgeChain;
import com.backend.boundedContext.settlement.out.SettlementCandidateRepository;
import com.backend.boundedContext.settlement.out.SettlementMemberPort;
import com.backend.boundedContext.settlement.out.SettlementPaymentPort;
import com.backend.boundedContext.settlement.out.SettlementProcessHistoryRepository;
import com.backend.boundedContext.settlement.out.SettlementRepository;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.payment.dto.TransferResultDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 원장 재대조(매일 새벽 스케줄러)와 판별 단계를 DB 없이 검증한다.
 * Repository·Port는 JDK 동적 프록시로 쓰는 메서드만 흉내 낸다.
 */
class SettlementLedgerRecheckAndJudgeUseCaseTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 0, 0);

    private final List<SettlementProcessHistory> histories = new ArrayList<>();
    private Settlement paid;
    private Optional<TransferResultDto> ledger = Optional.empty();
    private List<SettlementCandidate> readyCandidates = List.of();
    private SellerStatus sellerStatus = SellerStatus.APPROVED;
    private boolean otherMismatchRemains;                 // 이 판매자에게 다른 미해결 불일치가 남아 있는지
    private final List<Settlement> heldSettlements = new ArrayList<>();

    @Test
    @DisplayName("재대조: 원장이 영수증과 같으면 SUCCEEDED 이력")
    void recheckOk() {
        paid = paidSettlement();
        ledger = Optional.of(new TransferResultDto("SETTLEMENT-31", 501L, 18_000L, 2_000L, NOW));

        SettlementLedgerCheckResult result = recheckUseCase().recheck(31L, SettlementTrigger.LEDGER_RECHECK, null);

        assertEquals(SettlementLedgerCheckResult.OK, result);
        SettlementProcessHistory history = histories.get(0);
        assertEquals(SettlementHistoryStage.VERIFY, history.getStage());
        assertEquals(SettlementHistoryResult.SUCCEEDED, history.getResult());
    }

    @Test
    @DisplayName("재대조: 원장 기록이 사라졌으면 정산 데이터는 고치지 않고 MANUAL_REQUIRED 이력만 남긴다")
    void recheckMissing() {
        paid = paidSettlement();

        SettlementLedgerCheckResult result = recheckUseCase().recheck(31L, SettlementTrigger.MONTHLY_JOB, 9L);

        assertEquals(SettlementLedgerCheckResult.MISSING, result);
        SettlementProcessHistory history = histories.get(0);
        assertEquals(SettlementHistoryResult.MANUAL_REQUIRED, history.getResult());
        assertEquals("409-SETTLEMENT_LEDGER_MISSING", history.getErrorCode());
        assertEquals(9L, history.getBatchExecutionId());
        assertEquals(18_000L, paid.getPayoutAmount());   // 정산서는 그대로
    }

    @Test
    @DisplayName("재대조: 직전 결과와 같으면(같은 불일치, OK → OK) 이력을 다시 남기지 않는다")
    void recheckSameResultNotSavedTwice() {
        paid = paidSettlement();
        SettlementLedgerRecheckUseCase useCase = recheckUseCase();

        useCase.recheck(31L, SettlementTrigger.LEDGER_RECHECK, null);   // MISSING
        useCase.recheck(31L, SettlementTrigger.LEDGER_RECHECK, null);   // 또 MISSING
        assertEquals(1, histories.size());

        ledger = Optional.of(new TransferResultDto("SETTLEMENT-31", 999L, 18_000L, 2_000L, NOW));
        useCase.recheck(31L, SettlementTrigger.LEDGER_RECHECK, null);   // REPLACED: 불일치 종류가 바뀌면 남긴다
        assertEquals(2, histories.size());
        assertEquals("409-SETTLEMENT_LEDGER_REPLACED", histories.get(1).getErrorCode());

        histories.clear();
        ledger = Optional.of(new TransferResultDto("SETTLEMENT-31", 501L, 18_000L, 2_000L, NOW));
        otherMismatchRemains = true;   // 자동 해제는 이 테스트 관심사가 아니므로 막아 둔다
        useCase.recheck(31L, SettlementTrigger.LEDGER_RECHECK, null);   // OK(불일치 해소)
        useCase.recheck(31L, SettlementTrigger.LEDGER_RECHECK, null);   // 또 OK
        assertEquals(1, histories.size());
        assertEquals(SettlementHistoryResult.SUCCEEDED, histories.get(0).getResult());
    }

    @Test
    @DisplayName("재대조: 불일치가 풀리고 다른 불일치가 없으면 원장 불일치 사유로 보류된 정산서만 READY로 자동 해제(LEDGER_RECHECK 이력)")
    void recheckResolvedReleasesLedgerHolds() {
        paid = paidSettlement();
        histories.add(SettlementProcessHistory.ledgerChecked(31L, SettlementLedgerCheckResult.MISSING,
                SettlementTrigger.LEDGER_RECHECK, null, NOW.minusDays(5)));   // 5일 전 불일치
        Settlement ledgerHeld = heldSettlement(41L, LedgerMismatchRule.REASON);
        Settlement adminHeld = heldSettlement(42L, "관리자 확인 필요");
        Settlement sanctionHeld = heldSettlement(43L, SellerStatusRule.SANCTIONED_REASON);
        ledger = Optional.of(new TransferResultDto("SETTLEMENT-31", 501L, 18_000L, 2_000L, NOW));

        SettlementLedgerCheckResult result = recheckUseCase().recheck(31L, SettlementTrigger.LEDGER_RECHECK, null);

        assertEquals(SettlementLedgerCheckResult.OK, result);
        assertEquals(SettlementStatus.READY, ledgerHeld.getStatus());
        assertEquals(SettlementStatus.HOLD, adminHeld.getStatus());
        assertEquals(SettlementStatus.HOLD, sanctionHeld.getStatus());
        SettlementProcessHistory released = histories.get(histories.size() - 1);
        assertEquals(SettlementHistoryStage.RELEASE, released.getStage());
        assertEquals(41L, released.getTargetId());
        assertEquals(SettlementTrigger.LEDGER_RECHECK, released.getTriggerType());
    }

    @Test
    @DisplayName("재대조: 불일치가 풀려도 그 판매자에게 다른 불일치가 남아 있으면 보류를 유지한다")
    void recheckResolvedButOtherMismatchRemains() {
        paid = paidSettlement();
        histories.add(SettlementProcessHistory.ledgerChecked(31L, SettlementLedgerCheckResult.MISSING,
                SettlementTrigger.LEDGER_RECHECK, null, NOW.minusDays(5)));
        Settlement ledgerHeld = heldSettlement(41L, LedgerMismatchRule.REASON);
        otherMismatchRemains = true;
        ledger = Optional.of(new TransferResultDto("SETTLEMENT-31", 501L, 18_000L, 2_000L, NOW));

        recheckUseCase().recheck(31L, SettlementTrigger.LEDGER_RECHECK, null);

        assertEquals(SettlementStatus.HOLD, ledgerHeld.getStatus());
        assertEquals(SettlementHistoryResult.SUCCEEDED, histories.get(histories.size() - 1).getResult());   // OK 이력만
    }

    @Test
    @DisplayName("정리: 불일치가 없는 판매자면 원장 불일치 사유로 남은 보류만 READY로 되돌린다(재시작한 Job의 예전 판정, 해소와 엇갈린 보류)")
    void cleanupReleasesLeftoverLedgerHolds() {
        Settlement ledgerHeld = heldSettlement(41L, LedgerMismatchRule.REASON);
        Settlement adminHeld = heldSettlement(42L, "관리자 확인 필요");

        int released = recheckUseCase().releaseLedgerMismatchHoldsIfResolved(7L, SettlementTrigger.LEDGER_RECHECK);

        assertEquals(1, released);
        assertEquals(SettlementStatus.READY, ledgerHeld.getStatus());
        assertEquals(SettlementStatus.HOLD, adminHeld.getStatus());
        assertEquals(SettlementHistoryStage.RELEASE, histories.get(0).getStage());
        assertEquals(SettlementTrigger.LEDGER_RECHECK, histories.get(0).getTriggerType());
    }

    @Test
    @DisplayName("정리: 불일치가 아직 남은 판매자는 보류를 그대로 둔다")
    void cleanupKeepsHoldsWhileMismatchRemains() {
        Settlement ledgerHeld = heldSettlement(41L, LedgerMismatchRule.REASON);
        otherMismatchRemains = true;

        int released = recheckUseCase().releaseLedgerMismatchHoldsIfResolved(7L, SettlementTrigger.LEDGER_RECHECK);

        assertEquals(0, released);
        assertEquals(SettlementStatus.HOLD, ledgerHeld.getStatus());
        assertTrue(histories.isEmpty());
    }

    @Test
    @DisplayName("판별: 원장 불일치가 남은 판매자는 HOLD, 판매자 단위 이력(SELLER·JUDGE·SKIPPED)을 남긴다")
    void judgeLedgerMismatch() {
        readyCandidates = List.of(SettlementCandidate.create(100L, 1001L, 7L, 10_000L, 10, NOW));

        SettlementJudgement judgement = judgeUseCase().judgeSeller(7L, YearMonth.of(2026, 9), Set.of(7L),
                SettlementTrigger.MONTHLY_JOB, 9L);

        assertEquals(SettlementJudgement.Type.HOLD, judgement.type());
        SettlementProcessHistory history = histories.get(0);
        assertEquals(SettlementHistoryTargetType.SELLER, history.getTargetType());
        assertEquals(7L, history.getTargetId());
        assertEquals(SettlementHistoryStage.JUDGE, history.getStage());
        assertEquals(SettlementHistoryResult.SKIPPED, history.getResult());
    }

    @Test
    @DisplayName("판별: READY 후보가 없으면 PASS이고 이력을 남기지 않는다")
    void judgeNothing() {
        SettlementJudgement judgement = judgeUseCase().judgeSeller(7L, YearMonth.of(2026, 9), Set.of(),
                SettlementTrigger.MONTHLY_JOB, 9L);

        assertTrue(judgement.isPass());
        assertTrue(histories.isEmpty());
    }

    private SettlementLedgerRecheckUseCase recheckUseCase() {
        SettlementRepository settlementRepository = fake(SettlementRepository.class, Map.of(
                "findById", args -> Optional.ofNullable(paid),
                "existsUnresolvedLedgerMismatchBySellerId", args -> otherMismatchRemains,
                "findWithLockBySellerIdAndStatusIn", args -> heldSettlements.stream()
                        .filter(h -> ((Collection<?>) args[1]).contains(h.getStatus()))
                        .toList()));
        SettlementPaymentPort paymentPort = fake(SettlementPaymentPort.class, Map.of(
                "findTransfer", args -> ledger));
        return new SettlementLedgerRecheckUseCase(settlementRepository, paymentPort, historyRepository());
    }

    private SettlementJudgeUseCase judgeUseCase() {
        SettlementCandidateRepository candidateRepository = fake(SettlementCandidateRepository.class, Map.of(
                "findReadyBySellerIdUntilMonth", args -> readyCandidates));
        SettlementMemberPort memberPort = fake(SettlementMemberPort.class, Map.of(
                "getSellers", args -> List.of(new SellerDto(7L, 2L, "팔레트상점", sellerStatus))));
        SettlementJudgeChain chain = new SettlementJudgeChain(List.of(
                new CandidateAmountIntegrityRule(), new LedgerMismatchRule(), new SellerStatusRule()));
        return new SettlementJudgeUseCase(candidateRepository, fake(SettlementRepository.class, Map.of()),
                historyRepository(), memberPort, chain);
    }

    private SettlementProcessHistoryRepository historyRepository() {
        return fake(SettlementProcessHistoryRepository.class, Map.of(
                "save", args -> {
                    histories.add((SettlementProcessHistory) args[0]);
                    return args[0];
                },
                "findFirstByTargetTypeAndTargetIdAndStageOrderByIdDesc", args -> histories.stream()
                        .filter(h -> h.getTargetType() == args[0] && h.getTargetId().equals(args[1])
                                && h.getStage() == args[2])
                        .reduce((first, second) -> second)));
    }

    private Settlement heldSettlement(Long id, String holdReason) {
        Settlement settlement = Settlement.create(7L, "2026-10", holdReason);
        ReflectionTestUtils.setField(settlement, "id", id);
        heldSettlements.add(settlement);
        return settlement;
    }

    private static Settlement paidSettlement() {
        Settlement settlement = Settlement.create(7L, "2026-09", null);
        settlement.addCandidate(SettlementCandidate.create(100L, 1001L, 7L, 20_000L, 10, NOW.minusDays(20)));
        settlement.markPaid(501L, NOW);
        return settlement;
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
