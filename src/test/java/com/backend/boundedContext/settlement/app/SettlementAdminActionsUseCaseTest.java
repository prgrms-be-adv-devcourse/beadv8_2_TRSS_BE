package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import com.backend.boundedContext.settlement.domain.SettlementCandidateStatus;
import com.backend.boundedContext.settlement.domain.SettlementHistoryStage;
import com.backend.boundedContext.settlement.domain.SettlementHistoryTargetType;
import com.backend.boundedContext.settlement.domain.SettlementProcessHistory;
import com.backend.boundedContext.settlement.domain.SettlementSellerStatus;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.in.dto.SellerSettlementDetailResponse;
import com.backend.boundedContext.settlement.in.dto.SettlementCandidateResponse;
import com.backend.boundedContext.settlement.in.dto.SettlementResponse;
import com.backend.boundedContext.settlement.out.SettlementCandidateRepository;
import com.backend.boundedContext.settlement.out.SettlementProcessHistoryRepository;
import com.backend.boundedContext.settlement.out.SettlementRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
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
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 보류·해제·제재 보류·후보 제외·판매자 상세·미정산 금액을 DB 없이 검증한다.
 * Repository·MemberApi는 SettlementPayoutUseCaseTest와 같은 방식(동적 프록시 메모리 가짜)으로 흉내 낸다.
 */
class SettlementAdminActionsUseCaseTest {

    private static final Long SELLER_ID = 7L;
    private static final Long SELLER_MEMBER_ID = 2L;
    private static final Long OTHER_SELLER_ID = 8L;
    private static final Long OTHER_MEMBER_ID = 4L;

    private final Map<Long, Settlement> settlements = new HashMap<>();
    private final Map<Long, SettlementCandidate> candidates = new HashMap<>();
    private final List<SettlementProcessHistory> histories = new ArrayList<>();
    private Long unsettledSum;

    private final SettlementSupport support;
    private final SettlementHoldUseCase holdUseCase;
    private final SettlementReleaseUseCase releaseUseCase;
    private final SettlementHoldSellerUseCase holdSellerUseCase;
    private final SettlementExcludeCandidateUseCase excludeUseCase;
    private final SettlementGetSellerSettlementUseCase sellerDetailUseCase;

    SettlementAdminActionsUseCaseTest() {
        SettlementRepository settlementRepository = fake(SettlementRepository.class, Map.of(
                "findById", args -> Optional.ofNullable(settlements.get((Long) args[0])),
                "findWithLockById", args -> Optional.ofNullable(settlements.get((Long) args[0])),
                "findByIdAndSellerId", args -> Optional.ofNullable(settlements.get((Long) args[0]))
                        .filter(s -> s.getSellerId().equals(args[1])),
                "findWithLockBySellerIdAndStatusIn", args -> settlements.values().stream()
                        .filter(s -> s.getSellerId().equals(args[0]))
                        .filter(s -> ((Collection<?>) args[1]).contains(s.getStatus()))
                        .toList()));
        SettlementCandidateRepository candidateRepository = fake(SettlementCandidateRepository.class, Map.of(
                "findWithLockById", args -> Optional.ofNullable(candidates.get((Long) args[0])),
                "findBySettlementIdOrderByIdAsc", args -> candidates.values().stream()
                        .filter(c -> Objects.equals(c.getSettlementId(), args[0]))
                        .toList(),
                "sumUnsettledPayout", args -> unsettledSum));
        SettlementProcessHistoryRepository historyRepository = fake(SettlementProcessHistoryRepository.class,
                Map.of("save", args -> {
                    histories.add((SettlementProcessHistory) args[0]);
                    return args[0];
                }));
        MemberApi memberApi = fake(MemberApi.class, Map.of(
                "findSellerByMemberId", args -> {
                    if (SELLER_MEMBER_ID.equals(args[0])) {
                        return Optional.of(new SellerDto(SELLER_ID, SELLER_MEMBER_ID, "팔레트상점", SellerStatus.APPROVED));
                    }
                    if (OTHER_MEMBER_ID.equals(args[0])) {
                        return Optional.of(new SellerDto(OTHER_SELLER_ID, OTHER_MEMBER_ID, "다른상점", SellerStatus.APPROVED));
                    }
                    return Optional.empty();
                }));
        support = new SettlementSupport(settlementRepository, candidateRepository, historyRepository, memberApi);
        holdUseCase = new SettlementHoldUseCase(support, historyRepository);
        releaseUseCase = new SettlementReleaseUseCase(support, historyRepository);
        holdSellerUseCase = new SettlementHoldSellerUseCase(settlementRepository, historyRepository);
        excludeUseCase = new SettlementExcludeCandidateUseCase(support, historyRepository);
        sellerDetailUseCase = new SettlementGetSellerSettlementUseCase(support);
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
    @DisplayName("후보 제외: READY → EXCLUDED + EXCLUDE 이력(후보 대상), 묶인 후보는 409, 없는 후보는 404")
    void excludeCandidate() {
        SettlementCandidate ready = candidate(1001L);
        ReflectionTestUtils.setField(ready, "id", 501L);
        candidates.put(501L, ready);

        SettlementCandidateResponse result = excludeUseCase.exclude(501L, " 테스트 주문 ");

        assertEquals(SettlementCandidateStatus.EXCLUDED, result.status());
        assertEquals("테스트 주문", result.excludedReason());
        SettlementProcessHistory h = histories.get(0);
        assertEquals(SettlementHistoryTargetType.CANDIDATE, h.getTargetType());
        assertEquals(SettlementHistoryStage.EXCLUDE, h.getStage());

        assertEquals("409-SETTLEMENT_CANDIDATE_STATE_INVALID",
                assertThrows(DomainException.class, () -> excludeUseCase.exclude(501L, "다시")).getResultCode());
        assertEquals("404-SETTLEMENT_CANDIDATE_NOT_FOUND",
                assertThrows(DomainException.class, () -> excludeUseCase.exclude(999L, "사유")).getResultCode());
    }

    @Test
    @DisplayName("판매자 상세: 본인 정산서는 품목별 내역과 이월 표시, 남의 정산서·판매자 아님은 404")
    void sellerDetail() {
        Settlement s = Settlement.create(SELLER_ID, "2026-09", null);
        SettlementCandidate lateAugust = SettlementCandidate.create(90L, 900L, SELLER_ID, 10_000L, 10,
                LocalDateTime.of(2026, 8, 31, 23, 0));   // 8월 확정분이 9월 정산서로 이월
        SettlementCandidate september = candidate(901L);
        ReflectionTestUtils.setField(lateAugust, "id", 1L);
        ReflectionTestUtils.setField(september, "id", 2L);
        s.addCandidate(lateAugust);
        s.addCandidate(september);
        ReflectionTestUtils.setField(s, "id", 31L);
        s.markFailed("일시 장애", LocalDateTime.now(), 3, 10);
        settlements.put(31L, s);
        candidates.put(1L, lateAugust);
        candidates.put(2L, september);

        SellerSettlementDetailResponse detail = sellerDetailUseCase.getSellerSettlement(SELLER_MEMBER_ID, 31L);

        assertEquals(SettlementSellerStatus.DELAYED, detail.settlement().status());   // FAILED는 지급 지연으로만
        assertEquals(18_000L, detail.settlement().payoutAmount());
        assertEquals(2, detail.items().size());
        assertTrue(detail.items().stream().anyMatch(i -> i.carriedOver() && "2026-08".equals(i.settlementMonth())));
        assertFalse(detail.items().stream().allMatch(i -> i.carriedOver()));

        assertEquals("404-SETTLEMENT_NOT_FOUND", assertThrows(DomainException.class,
                () -> sellerDetailUseCase.getSellerSettlement(OTHER_MEMBER_ID, 31L)).getResultCode());
        assertEquals("404-SETTLEMENT_NOT_FOUND", assertThrows(DomainException.class,
                () -> sellerDetailUseCase.getSellerSettlement(99L, 31L)).getResultCode());
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

    private static SettlementCandidate candidate(Long orderItemId) {
        return SettlementCandidate.create(orderItemId, orderItemId, SELLER_ID, 10_000L, 10,
                LocalDateTime.of(2026, 9, 15, 12, 0));
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