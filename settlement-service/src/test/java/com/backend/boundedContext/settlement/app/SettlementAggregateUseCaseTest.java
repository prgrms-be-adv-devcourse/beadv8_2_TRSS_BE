package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import com.backend.boundedContext.settlement.domain.SettlementCandidateStatus;
import com.backend.boundedContext.settlement.domain.SettlementHistoryResult;
import com.backend.boundedContext.settlement.domain.SettlementHistoryStage;
import com.backend.boundedContext.settlement.domain.SettlementHistoryTargetType;
import com.backend.boundedContext.settlement.domain.SettlementJudgement;
import com.backend.boundedContext.settlement.domain.SettlementProcessHistory;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.domain.judge.CandidateAmountIntegrityRule;
import com.backend.boundedContext.settlement.out.SettlementCandidateRepository;
import com.backend.boundedContext.settlement.out.SettlementMemberPort;
import com.backend.boundedContext.settlement.out.SettlementProcessHistoryRepository;
import com.backend.boundedContext.settlement.out.SettlementRepository;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 확정 직전 후보 금액 확인을 DB 없이 검증한다. 판정이 없을 때(판별 중 오류, 재시작)와
 * 판정이 있을 때(판별 뒤 들어온 후보) 모두 잠근 후보로 다시 확인한다(코드리뷰 변경 제안 8).
 * Repository·Port는 JDK 동적 프록시로 쓰는 메서드만 흉내 낸다.
 */
class SettlementAggregateUseCaseTest {

    private static final Long SELLER_ID = 7L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 0, 0);

    private final List<SettlementProcessHistory> histories = new ArrayList<>();
    private final List<Settlement> saved = new ArrayList<>();
    private List<SettlementCandidate> readyCandidates = List.of();

    @Test
    @DisplayName("판정이 없을 때 후보 금액 스냅샷이 어긋나면 확정하지 않고(정산서 없음) 후보 BLOCK·확정 실패 이력을 남긴다")
    void noJudgement_tamperedCandidate_blocked() {
        SettlementCandidate ok = candidate(1L, 10_000L);
        SettlementCandidate tampered = candidate(2L, 10_000L);
        ReflectionTestUtils.setField(tampered, "payoutAmount", 99_000L);   // DB에서 지급액을 직접 고친 경우
        readyCandidates = List.of(ok, tampered);

        int aggregated = useCase().aggregateSeller(SELLER_ID, YearMonth.of(2026, 9), 9L, SettlementTrigger.MONTHLY_JOB,
                null);

        assertEquals(0, aggregated);
        assertTrue(saved.isEmpty());
        assertEquals(SettlementCandidateStatus.READY, tampered.getStatus());   // 후보는 다음 실행에서 다시 본다
        SettlementProcessHistory blocked = histories.get(0);
        assertEquals(SettlementHistoryTargetType.CANDIDATE, blocked.getTargetType());
        assertEquals(2L, blocked.getTargetId());
        assertEquals(SettlementHistoryResult.MANUAL_REQUIRED, blocked.getResult());
        assertEquals("409-SETTLEMENT_CANDIDATE_AMOUNT_TAMPERED", blocked.getErrorCode());
        assertEquals(SettlementHistoryStage.AGGREGATE, histories.get(1).getStage());
        assertEquals(SettlementHistoryResult.FAILED, histories.get(1).getResult());
    }

    @Test
    @DisplayName("판정이 없어도 후보 금액이 맞으면 예전처럼 정산서를 만든다")
    void noJudgement_consistentCandidates_aggregated() {
        readyCandidates = List.of(candidate(1L, 10_000L), candidate(2L, 15_000L));

        int aggregated = useCase().aggregateSeller(SELLER_ID, YearMonth.of(2026, 9), 9L, SettlementTrigger.MONTHLY_JOB,
                null);

        assertEquals(2, aggregated);
        assertEquals(1, saved.size());
        assertEquals(SettlementStatus.READY, saved.get(0).getStatus());
    }

    @Test
    @DisplayName("PASS 판정을 받은 판매자라도 잠근 후보에 금액이 맞지 않는 후보가 섞여 있으면 묶지 않는다 (판별 뒤 들어온 후보, 재시작)")
    void passJudgement_tamperedCandidate_blocked() {
        SettlementCandidate ok = candidate(1L, 10_000L);
        SettlementCandidate lateTampered = candidate(3L, 20_000L);   // 판별 뒤 들어온 후보라고 가정
        ReflectionTestUtils.setField(lateTampered, "fee", 0L);
        readyCandidates = List.of(ok, lateTampered);

        int aggregated = useCase().aggregateSeller(SELLER_ID, YearMonth.of(2026, 9), 9L, SettlementTrigger.MONTHLY_JOB,
                SettlementJudgement.pass());

        assertEquals(0, aggregated);
        assertTrue(saved.isEmpty());
        assertEquals(SettlementCandidateStatus.READY, ok.getStatus());
        assertEquals(SettlementCandidateStatus.READY, lateTampered.getStatus());
        assertEquals(3L, histories.get(0).getTargetId());
        assertEquals("409-SETTLEMENT_CANDIDATE_AMOUNT_TAMPERED", histories.get(0).getErrorCode());
        assertTrue(histories.get(1).getFailReason().contains("판정 PASS"));
    }

    @Test
    @DisplayName("HOLD 판정이어도 금액이 맞지 않는 후보가 있으면 보류 정산서를 만들지 않고 막는다")
    void holdJudgement_tamperedCandidate_blocked() {
        SettlementCandidate tampered = candidate(4L, 10_000L);
        ReflectionTestUtils.setField(tampered, "salePrice", 1_000_000L);
        readyCandidates = List.of(tampered);

        int aggregated = useCase().aggregateSeller(SELLER_ID, YearMonth.of(2026, 9), 9L, SettlementTrigger.MONTHLY_JOB,
                SettlementJudgement.hold("판매자 제재"));

        assertEquals(0, aggregated);
        assertTrue(saved.isEmpty());
    }

    @Test
    @DisplayName("PASS 판정이고 후보 금액이 맞으면 정산서를 만든다")
    void passJudgement_consistentCandidates_aggregated() {
        readyCandidates = List.of(candidate(1L, 10_000L), candidate(2L, 15_000L));

        int aggregated = useCase().aggregateSeller(SELLER_ID, YearMonth.of(2026, 9), 9L, SettlementTrigger.MONTHLY_JOB,
                SettlementJudgement.pass());

        assertEquals(2, aggregated);
        assertEquals(SettlementStatus.READY, saved.get(0).getStatus());
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
        SettlementMemberPort memberPort = fake(SettlementMemberPort.class, Map.of(
                "getSellers", args -> List.of(new SellerDto(SELLER_ID, 2L, "팔레트상점", SellerStatus.APPROVED))));
        return new SettlementAggregateUseCase(settlementRepository, candidateRepository, historyRepository, memberPort,
                new CandidateAmountIntegrityRule());
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
