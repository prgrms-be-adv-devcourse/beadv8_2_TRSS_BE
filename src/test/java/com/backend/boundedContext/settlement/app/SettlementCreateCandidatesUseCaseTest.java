package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import com.backend.boundedContext.settlement.domain.SettlementCandidateStatus;
import com.backend.boundedContext.settlement.domain.SettlementHistoryResult;
import com.backend.boundedContext.settlement.domain.SettlementProcessHistory;
import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import com.backend.boundedContext.settlement.out.SettlementCandidateRepository;
import com.backend.boundedContext.settlement.out.SettlementProcessHistoryRepository;
import com.backend.shared.order.dto.ConfirmedOrderItemDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 구매 확정 → 정산 후보 생성을 DB 없이 검증한다.
 * Repository는 JDK 동적 프록시로 만든 메모리 가짜이며, existsByOrderItemId·save만 흉내 낸다.
 * 처리 이력도 메모리 목록에 쌓아 성공·실패가 기록되는지 확인한다
 */
class SettlementCreateCandidatesUseCaseTest {

    private static final LocalDateTime CONFIRMED_AT = LocalDateTime.of(2026, 9, 30, 23, 59, 59);

    private final List<SettlementCandidate> table = new ArrayList<>();
    private final List<SettlementProcessHistory> histories = new ArrayList<>();
    private final SettlementCreateCandidatesUseCase useCase;

    SettlementCreateCandidatesUseCaseTest() {
        useCase = new SettlementCreateCandidatesUseCase(fakeRepository(), fakeHistoryRepository(),
                new SettlementPolicy(10, 10, 3, 10, 100, 50) /* 10/14 리뷰 반영: 정적 값 대입 대신 정책 객체를 직접 만들어 넘긴다(수수료율 10%) */);
    }

    @Test
    @DisplayName("주문 항목 수만큼 READY 후보를 만들고 수수료·지급액·정산월을 계산한다")
    void create() {
        int created = useCase.createCandidates(100L, CONFIRMED_AT, List.of(
                item(1001L, 7L, 9_999L),
                item(1002L, 8L, 20_000L)));

        assertEquals(2, created);
        assertEquals(2, table.size());
        SettlementCandidate first = table.get(0);
        assertEquals(100L, first.getOrderId());
        assertEquals(1001L, first.getOrderItemId());
        assertEquals(7L, first.getSellerId());
        assertEquals(999L, first.getFee());                 // 999.9 → 999 (버림)
        assertEquals(9_000L, first.getPayoutAmount());
        assertEquals(10, first.getFeeRate());
        assertEquals("2026-09", first.getSettlementMonth()); // 9/30 23:59:59 KST → 9월
        assertEquals(SettlementCandidateStatus.READY, first.getStatus());
        assertEquals(8L, table.get(1).getSellerId());
        assertEquals(2, histories.size());                  // 후보마다 성공 이력
        assertEquals(SettlementHistoryResult.SUCCEEDED, histories.get(0).getResult());
    }

    @Test
    @DisplayName("같은 확정 이벤트를 두 번 받아도 후보는 한 번만 생긴다")
    void create_duplicateEvent() {
        List<ConfirmedOrderItemDto> items = List.of(item(1001L, 7L, 10_000L), item(1002L, 7L, 10_000L));

        assertEquals(2, useCase.createCandidates(100L, CONFIRMED_AT, items));
        assertEquals(0, useCase.createCandidates(100L, CONFIRMED_AT, items));
        assertEquals(2, table.size());
    }

    @Test
    @DisplayName("이미 있는 항목만 건너뛰고 새 항목은 만든다")
    void create_partialDuplicate() {
        useCase.createCandidates(100L, CONFIRMED_AT, List.of(item(1001L, 7L, 10_000L)));

        int created = useCase.createCandidates(100L, CONFIRMED_AT,
                List.of(item(1001L, 7L, 10_000L), item(1002L, 7L, 5_000L)));

        assertEquals(1, created);
        assertEquals(2, table.size());
    }

    @Test
    @DisplayName("항목이 없으면 아무것도 만들지 않는다")
    void create_empty() {
        assertEquals(0, useCase.createCandidates(100L, CONFIRMED_AT, List.of()));
        assertEquals(0, table.size());
    }

    @Test
    @DisplayName("단가 × 수량이 항목 금액과 다르거나 0원 이하면 후보를 만들지 않고 실패 이력만 남긴다")
    void create_invalidAmount() {
        int created = useCase.createCandidates(100L, CONFIRMED_AT, List.of(
                new ConfirmedOrderItemDto(1001L, 1L, 7L, 5_000L, 3, 14_000L),   // 5,000 × 3 ≠ 14,000
                new ConfirmedOrderItemDto(1002L, 1L, 7L, 0L, 1, 0L),            // 0원
                item(1003L, 7L, 10_000L)));                                    // 정상

        assertEquals(1, created);
        assertEquals(1, table.size());
        assertEquals(1003L, table.get(0).getOrderItemId());
        assertEquals(3, histories.size());
        SettlementProcessHistory rejected = histories.get(0);
        assertEquals(SettlementHistoryResult.FAILED, rejected.getResult());
        assertEquals("400-SETTLEMENT_CANDIDATE_AMOUNT_INVALID", rejected.getErrorCode());
        assertNull(rejected.getTargetId());
        assertTrue(rejected.getFailReason().contains("orderItemId=1001"));
    }

    private static ConfirmedOrderItemDto item(Long orderItemId, Long sellerId, long lineAmount) {
        return new ConfirmedOrderItemDto(orderItemId, 1L, sellerId, lineAmount, 1, lineAmount);
    }

    private SettlementProcessHistoryRepository fakeHistoryRepository() {
        return (SettlementProcessHistoryRepository) Proxy.newProxyInstance(
                SettlementProcessHistoryRepository.class.getClassLoader(),
                new Class<?>[]{SettlementProcessHistoryRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "save" -> {
                        histories.add((SettlementProcessHistory) args[0]);
                        yield args[0];
                    }
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "SettlementProcessHistoryRepositoryFake";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private SettlementCandidateRepository fakeRepository() {
        return (SettlementCandidateRepository) Proxy.newProxyInstance(
                SettlementCandidateRepository.class.getClassLoader(),
                new Class<?>[]{SettlementCandidateRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "existsByOrderItemId" -> table.stream()
                            .anyMatch(c -> c.getOrderItemId().equals(args[0]));
                    case "save" -> {
                        table.add((SettlementCandidate) args[0]);
                        yield args[0];
                    }
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "SettlementCandidateRepositoryFake";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}