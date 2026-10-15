package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementHistoryStage;
import com.backend.boundedContext.settlement.domain.SettlementProcessHistory;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.in.dto.SettlementResponse;
import com.backend.boundedContext.settlement.out.SettlementCandidateRepository;
import com.backend.boundedContext.settlement.out.SettlementProcessHistoryRepository;
import com.backend.boundedContext.settlement.out.SettlementRepository;
import com.backend.global.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 10/15 추가 기능(보류·해제·제재 보류·미정산 금액)을 DB 없이 검증한다.
 * Repository는 SettlementPayoutUseCaseTest와 같은 방식(동적 프록시 메모리 가짜)으로 흉내 낸다.
 */
class SettlementAdminActionsUseCaseTest {

    private static final Long SELLER_ID = 7L;
    private static final Long OTHER_SELLER_ID = 8L;

    private final Map<Long, Settlement> settlements = new HashMap<>();
    private final List<SettlementProcessHistory> histories = new ArrayList<>();
    private Long unsettledSum;

    private final SettlementSupport support;
    private final SettlementHoldUseCase holdUseCase;
    private final SettlementReleaseUseCase releaseUseCase;
    private final SettlementHoldSellerUseCase holdSellerUseCase;

    SettlementAdminActionsUseCaseTest() {
        SettlementRepository settlementRepository = fake(SettlementRepository.class, Map.of(
                "findById", args -> Optional.ofNullable(settlements.get((Long) args[0])),
                "findWithLockById", args -> Optional.ofNullable(settlements.get((Long) args[0])),
                "findWithLockBySellerIdAndStatusIn", args -> settlements.values().stream()
                        .filter(s -> s.getSellerId().equals(args[0]))
                        .filter(s -> ((Collection<?>) args[1]).contains(s.getStatus()))
                        .toList()));
        SettlementCandidateRepository candidateRepository = fake(SettlementCandidateRepository.class, Map.of(
                "sumUnsettledPayout", args -> unsettledSum));
        SettlementProcessHistoryRepository historyRepository = fake(SettlementProcessHistoryRepository.class,
                Map.of("save", args -> {
                    histories.add((SettlementProcessHistory) args[0]);
                    return args[0];
                }));
        support = new SettlementSupport(settlementRepository, candidateRepository, historyRepository);
        holdUseCase = new SettlementHoldUseCase(support, historyRepository);
        releaseUseCase = new SettlementReleaseUseCase(support, historyRepository);
        holdSellerUseCase = new SettlementHoldSellerUseCase(settlementRepository, historyRepository);
    }

    @Test
    @DisplayName("보류: READY → HOLD, 보류 사유(앞뒤 공백 제거)와 HOLD 이력(ADMIN)을 남긴다")
    void hold() {
        save(31L, SELLER_ID, "2026-09");

        SettlementResponse result = holdUseCase.hold(31L, "  판매자 확인 필요  ");

        assertEquals(SettlementStatus.HOLD, result.status());
        assertEquals("판매자 확인 필요", result.holdReason());
        SettlementProcessHistory h = histories.get(0);
        assertEquals(SettlementHistoryStage.HOLD, h.getStage());
        assertEquals(SettlementTrigger.ADMIN, h.getTriggerType());
        assertEquals("판매자 확인 필요", h.getAdminReason());
        assertEquals(31L, h.getTargetId());
    }

    @Test
    @DisplayName("보류: 없는 정산서 404, 이미 HOLD면 409이고 이력을 남기지 않는다")
    void hold_rejected() {
        assertEquals("404-SETTLEMENT_NOT_FOUND",
                assertThrows(DomainException.class, () -> holdUseCase.hold(999L, "사유")).getResultCode());

        save(31L, SELLER_ID, "2026-09").hold("먼저 보류");
        assertEquals("409-SETTLEMENT_STATE_INVALID",
                assertThrows(DomainException.class, () -> holdUseCase.hold(31L, "또 보류")).getResultCode());
        assertTrue(histories.isEmpty());
    }

    @Test
    @DisplayName("해제: HOLD → READY(사유 지움) + RELEASE 이력, HOLD가 아니면 409")
    void release() {
        save(31L, SELLER_ID, "2026-09").hold("보류");

        SettlementResponse result = releaseUseCase.release(31L);

        assertEquals(SettlementStatus.READY, result.status());
        assertNull(result.holdReason());
        assertEquals(SettlementHistoryStage.RELEASE, histories.get(0).getStage());
        assertEquals("409-SETTLEMENT_STATE_INVALID",
                assertThrows(DomainException.class, () -> releaseUseCase.release(31L)).getResultCode());
    }

    @Test
    @DisplayName("판매자 제재: 그 판매자의 지급 전 정산서만 HOLD, PAID·다른 판매자 정산서는 그대로")
    void holdSeller() {
        Settlement ready = save(31L, SELLER_ID, "2026-08");
        Settlement manual = save(32L, SELLER_ID, "2026-09");
        manual.markManualRequired("금액 불일치");
        Settlement paid = save(33L, SELLER_ID, "2026-07");
        paid.markPaid(500L, LocalDateTime.now());
        Settlement other = save(34L, OTHER_SELLER_ID, "2026-09");

        int held = holdSellerUseCase.holdSeller(SELLER_ID, "위조 상품 판매");

        assertEquals(2, held);
        assertEquals(SettlementStatus.HOLD, ready.getStatus());
        assertEquals(SettlementStatus.HOLD, manual.getStatus());
        assertEquals("위조 상품 판매", manual.getHoldReason());
        assertEquals(SettlementStatus.PAID, paid.getStatus());
        assertEquals(SettlementStatus.READY, other.getStatus());
        assertEquals(2, histories.size());
    }

    @Test
    @DisplayName("미정산 금액: 후보가 없으면(null) 0")
    void unsettledAmount() {
        unsettledSum = null;
        assertEquals(0L, support.sumUnsettledPayout(SELLER_ID));
        unsettledSum = 27_000L;
        assertEquals(27_000L, support.sumUnsettledPayout(SELLER_ID));
    }

    private Settlement save(Long id, Long sellerId, String month) {
        Settlement s = Settlement.create(sellerId, month, null);
        ReflectionTestUtils.setField(s, "id", id);
        s.assignIdempotencyKey();
        settlements.put(id, s);
        return s;
    }

    private interface Handler {
        Object handle(Object[] args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type, Map<String, Handler> handlers) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> type.getSimpleName() + "Fake";
                };
            }
            Handler handler = handlers.get(method.getName());
            if (handler == null) throw new UnsupportedOperationException(method.getName());
            return handler.handle(args == null ? new Object[0] : args);
        });
    }
}