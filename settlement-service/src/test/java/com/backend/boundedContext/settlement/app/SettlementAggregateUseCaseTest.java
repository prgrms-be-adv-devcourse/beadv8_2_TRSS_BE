package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import com.backend.boundedContext.settlement.domain.SettlementCandidateStatus;
import com.backend.boundedContext.settlement.domain.SettlementHistoryResult;
import com.backend.boundedContext.settlement.domain.SettlementHistoryStage;
import com.backend.boundedContext.settlement.domain.SettlementHistoryTargetType;
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
 * 판정 없이 확정할 때(판별 중 오류, 판별 이후 생긴 후보, 확정 단계부터 재시작) 후보 금액 확인을 DB 없이 검증한다.
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
