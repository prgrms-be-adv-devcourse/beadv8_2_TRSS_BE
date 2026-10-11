package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import com.backend.boundedContext.settlement.domain.SettlementCandidateStatus;
import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import com.backend.boundedContext.settlement.out.SettlementCandidateRepository;
import com.backend.shared.order.dto.ConfirmedOrderItemDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 구매 확정 → 정산 후보 생성을 DB 없이 검증한다.
 * Repository는 JDK 동적 프록시로 만든 메모리 가짜이며, existsByOrderItemId·save만 흉내 낸다.
 */
class SettlementCreateCandidatesUseCaseTest {

    private static final LocalDateTime CONFIRMED_AT = LocalDateTime.of(2026, 9, 30, 23, 59, 59);

    private final List<SettlementCandidate> table = new ArrayList<>();
    private final SettlementCreateCandidatesUseCase useCase;

    SettlementCreateCandidatesUseCaseTest() {
        SettlementPolicy.FEE_RATE = 10;
        useCase = new SettlementCreateCandidatesUseCase(fakeRepository());
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
    @DisplayName("판매가가 음수인 잘못된 항목은 예외로 거절한다")
    void create_invalidPrice() {
        assertThrows(IllegalArgumentException.class,
                () -> useCase.createCandidates(100L, CONFIRMED_AT, List.of(item(1001L, 7L, -1L))));
    }

    private static ConfirmedOrderItemDto item(Long orderItemId, Long sellerId, long lineAmount) {
        return new ConfirmedOrderItemDto(orderItemId, 1L, sellerId, lineAmount, 1, lineAmount);
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