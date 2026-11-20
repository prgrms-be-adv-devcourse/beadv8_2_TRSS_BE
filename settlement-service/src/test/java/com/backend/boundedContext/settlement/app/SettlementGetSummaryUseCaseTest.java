package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.in.dto.SellerSettlementSummaryResponse;
import com.backend.boundedContext.settlement.in.dto.SettlementSummaryResponse;
import com.backend.boundedContext.settlement.out.SettlementCandidateRepository;
import com.backend.boundedContext.settlement.out.SettlementMemberPort;
import com.backend.boundedContext.settlement.out.SettlementProcessHistoryRepository;
import com.backend.boundedContext.settlement.out.SettlementRepository;
import com.backend.boundedContext.settlement.out.SettlementStatusAmountRow;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 정산 요약 카드: 상태별 건수를 화면 묶음(처리 필요 = 재시도 중 + 수동 처리 필요)으로 바꾸는지 DB 없이 확인한다
 */
class SettlementGetSummaryUseCaseTest {

    private final List<Object[]> queries = new ArrayList<>();
    private Optional<SellerDto> seller = Optional.of(new SellerDto(7L, 2L, "팔레트상점", SellerStatus.APPROVED));
    private Optional<Settlement> latestPaid = Optional.empty();

    private final List<SettlementStatusAmountRow> rows = List.of(
            new SettlementStatusAmountRow(SettlementStatus.PAID, 38L, 24_820_000L),
            new SettlementStatusAmountRow(SettlementStatus.FAILED, 1L, 774_000L),
            new SettlementStatusAmountRow(SettlementStatus.MANUAL_REQUIRED, 1L, 373_500L),
            new SettlementStatusAmountRow(SettlementStatus.HOLD, 1L, 198_000L),
            new SettlementStatusAmountRow(SettlementStatus.READY, 3L, 900_000L));

    @Test
    @DisplayName("관리자: 처리 필요 = 재시도 중 + 수동 처리 필요, 지급 완료 금액은 PAID 지급액 합")
    void adminSummary() {
        SettlementSummaryResponse summary = useCase().getSummary("2026-10", null, null, null);

        assertEquals(44, summary.totalCount());
        assertEquals(38, summary.paidCount());
        assertEquals(24_820_000L, summary.paidAmount());
        assertEquals(2, summary.actionRequiredCount());
        assertEquals(1, summary.retryingCount());
        assertEquals(1, summary.manualRequiredCount());
        assertEquals(1, summary.onHoldCount());
        assertEquals(3, summary.scheduledCount());
        assertEquals("2026-10", queries.get(0)[0]);   // 정산월 하나 = from = to
        assertEquals("2026-10", queries.get(0)[1]);
    }

    @Test
    @DisplayName("관리자: 정산월과 기간을 같이 주면 400")
    void adminSummaryInvalidFilter() {
        DomainException e = assertThrows(DomainException.class, () -> useCase().getSummary("2026-10",
                YearMonth.of(2026, 9), YearMonth.of(2026, 10), null));
        assertEquals("400-SETTLEMENT_MONTH_RANGE_INVALID", e.getResultCode());
    }

    @Test
    @DisplayName("판매자: 본인 판매자 ID로 세고, 가장 최근 지급 정산서를 함께 준다")
    void sellerSummary() {
        Settlement paid = Settlement.create(7L, "2026-09", null);
        paid.markPaid(501L, LocalDateTime.of(2026, 10, 10, 0, 12));
        latestPaid = Optional.of(paid);

        SellerSettlementSummaryResponse summary = useCase().getSellerSummary(2L, YearMonth.of(2026, 5),
                YearMonth.of(2026, 10));

        assertEquals(7L, queries.get(0)[2]);
        assertEquals(38, summary.paidCount());
        assertEquals("2026-09", summary.latestPaid().settlementMonth());
    }

    @Test
    @DisplayName("판매자 신청 기록이 없으면 정산서를 세지 않고 0건")
    void sellerWithoutSellerRecord() {
        seller = Optional.empty();

        SellerSettlementSummaryResponse summary = useCase().getSellerSummary(2L, YearMonth.of(2026, 5),
                YearMonth.of(2026, 10));

        assertEquals(0, summary.paidCount());
        assertNull(summary.latestPaid());
        assertEquals(0, queries.size());
    }

    private SettlementGetSummaryUseCase useCase() {
        SettlementRepository settlementRepository = fake(SettlementRepository.class, Map.of(
                "sumByStatus", args -> {
                    queries.add(args);
                    return rows;
                },
                "findFirstBySellerIdAndStatusOrderByPaidAtDesc", args -> latestPaid));
        SettlementMemberPort memberPort = fake(SettlementMemberPort.class, Map.of(
                "findSellerByMemberId", args -> seller));
        SettlementSupport support = new SettlementSupport(settlementRepository,
                fake(SettlementCandidateRepository.class, Map.of()),
                fake(SettlementProcessHistoryRepository.class, Map.of()), memberPort);
        return new SettlementGetSummaryUseCase(settlementRepository, support,
                new SettlementPolicy(10, 10, 3, 10, 100, 50));
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
