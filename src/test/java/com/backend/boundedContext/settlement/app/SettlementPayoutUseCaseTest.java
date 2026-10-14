package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import com.backend.boundedContext.settlement.domain.SettlementHistoryResult;
import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import com.backend.boundedContext.settlement.domain.SettlementProcessHistory;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.in.dto.SettlementPayoutResult;
import com.backend.boundedContext.settlement.out.SettlementCandidateRepository;
import com.backend.boundedContext.settlement.out.SettlementCandidateTotalsRow;
import com.backend.boundedContext.settlement.out.SettlementOrderAmountRow;
import com.backend.boundedContext.settlement.out.SettlementProcessHistoryRepository;
import com.backend.boundedContext.settlement.out.SettlementRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.payment.dto.SettlementTransferCommand;
import com.backend.shared.payment.dto.TransferResultDto;
import com.backend.shared.payment.out.PaymentApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 지급(하나의 지급 트랜잭션 + 별도 실패 기록 트랜잭션)을 DB 없이 검증한다.
 * Repository·PaymentApi·MemberApi는 메모리 가짜이고, 트랜잭션은 ResourcelessTransactionManager로 흉내 낸다.
 * PaymentApi 가짜는 동적 프록시로 transfer·findTransfer만 흉내 내므로, 결제 쪽 계약에 메서드가 더 있어도 이 테스트는 고칠 필요가 없다.
 */
class SettlementPayoutUseCaseTest {

    private static final Long SETTLEMENT_ID = 31L;
    private static final Long SELLER_ID = 7L;
    private static final Long SELLER_MEMBER_ID = 2L;

    private final Map<Long, Settlement> table = new HashMap<>();
    private final List<SettlementProcessHistory> histories = new ArrayList<>();
    private final FakePayment paymentApi = new FakePayment();
    private SettlementCandidateTotalsRow totals;
    private Supplier<SellerDto> seller = () -> new SellerDto(SELLER_ID, SELLER_MEMBER_ID, "팔레트상점", SellerStatus.APPROVED);
    private final SettlementPayoutUseCase useCase;

    SettlementPayoutUseCaseTest() {
        SettlementRepository settlementRepository = fake(SettlementRepository.class, Map.of(
                "findById", args -> Optional.ofNullable(table.get((Long) args[0])),
                "findWithLockById", args -> Optional.ofNullable(table.get((Long) args[0]))));
        SettlementCandidateRepository candidateRepository = fake(SettlementCandidateRepository.class, Map.of(
                "sumIncludedTotals", args -> totals,
                "sumSalePriceByOrder", args -> List.of(new SettlementOrderAmountRow(101L, 10_000L),
                        new SettlementOrderAmountRow(102L, 10_000L))));
        SettlementProcessHistoryRepository historyRepository = fake(SettlementProcessHistoryRepository.class,
                Map.of("save", args -> {
                    histories.add((SettlementProcessHistory) args[0]);
                    return args[0];
                }));
        MemberApi memberApi = fake(MemberApi.class, Map.of("getSeller", args -> seller.get()));
        useCase = new SettlementPayoutUseCase(new SettlementSupport(settlementRepository, candidateRepository),
                candidateRepository, historyRepository, fakePaymentApi(), memberApi,
                new SettlementPolicy(10, 10, 3, 10, 100, 50) /* 10/14 리뷰 반영: 정적 값 대입 대신 정책 객체를 직접 만들어 넘긴다 */, new ResourcelessTransactionManager());
    }

    @Test
    @DisplayName("READY 정산서: 합계 확인 → 같은 멱등 키로 지급 → PAID + 성공 이력")
    void payout_success() {
        Settlement s = saveReady();

        SettlementPayoutResult result = useCase.payout(SETTLEMENT_ID, SettlementTrigger.MONTHLY_JOB, 900L, null);

        assertTrue(result.isPaid());
        assertEquals(SettlementStatus.PAID, s.getStatus());
        assertNotNull(s.getPaidAt());
        SettlementTransferCommand cmd = paymentApi.lastCommand;
        assertEquals("SETTLEMENT-31", cmd.idempotencyKey());
        assertEquals(SELLER_MEMBER_ID, cmd.sellerMemberId());
        assertEquals(18_000L, cmd.payoutAmount());
        assertEquals(2_000L, cmd.feeAmount());
        assertEquals(20_000L, cmd.orders().stream().mapToLong(o -> o.amount()).sum());   // 주문별 합 = 지급액 + 수수료
        SettlementProcessHistory h = histories.get(0);
        assertEquals(SettlementHistoryResult.SUCCEEDED, h.getResult());
        assertEquals(1, h.getAttemptNo());
        assertEquals(SettlementTrigger.MONTHLY_JOB, h.getTriggerType());
        assertEquals(900L, h.getBatchExecutionId());
    }

    @Test
    @DisplayName("관리자가 수동 실행한 월 정산 Job: 지급 기준은 자동 지급과 같고(READY 지급, 일시 오류는 FAILED) 이력은 ADMIN")
    void payout_adminRunJob() {
        Settlement s = saveReady();
        paymentApi.transferError = () -> new IllegalStateException("일시 장애");

        useCase.payout(SETTLEMENT_ID, SettlementTrigger.ADMIN, 900L, null);
        assertEquals(SettlementStatus.FAILED, s.getStatus());   // 관리자 재처리처럼 바로 MANUAL_REQUIRED가 되지 않는다
        assertEquals(SettlementTrigger.ADMIN, histories.get(0).getTriggerType());

        paymentApi.transferError = null;
        s.hold("확인");
        s.release();   // FAILED의 재시도 일시를 기다리지 않도록 보류·해제로 READY로 되돌린다
        SettlementPayoutResult result = useCase.payout(SETTLEMENT_ID, SettlementTrigger.ADMIN, 900L, null);
        assertTrue(result.isPaid());
        assertEquals(SettlementTrigger.ADMIN, histories.get(histories.size() - 1).getTriggerType());
    }

    @Test
    @DisplayName("지급 중 일시 오류: 롤백 후 별도 트랜잭션에서 FAILED + 다음 재시도 일시 + 실패 이력")
    void payout_transientFailure() {
        Settlement s = saveReady();
        paymentApi.transferError = () -> new IllegalStateException("DB 타임아웃");

        SettlementPayoutResult result = useCase.payout(SETTLEMENT_ID, SettlementTrigger.MONTHLY_JOB, null, null);

        assertTrue(result.processed());
        assertEquals(SettlementStatus.FAILED, s.getStatus());
        assertEquals(1, s.getRetryCount());
        assertNotNull(s.getNextRetryAt());
        SettlementProcessHistory h = histories.get(0);
        assertEquals(SettlementHistoryResult.FAILED, h.getResult());
        assertEquals("IllegalStateException", h.getErrorCode());
        assertFalse(h.getFailReason().contains("DB 타임아웃"));   // 사유에는 내부 메시지를 넣지 않음
    }

    @Test
    @DisplayName("정산서 합계 ≠ 후보 합계: 송금하지 않고 바로 MANUAL_REQUIRED(재시도 불가)")
    void payout_totalsMismatch() {
        Settlement s = saveReady();
        totals = new SettlementCandidateTotalsRow(20_000L, 2_000L, 17_000L, 2L);

        useCase.payout(SETTLEMENT_ID, SettlementTrigger.MONTHLY_JOB, null, null);

        assertEquals(SettlementStatus.MANUAL_REQUIRED, s.getStatus());
        assertEquals(0, paymentApi.transferCalls);
        assertEquals("409-SETTLEMENT_AMOUNT_MISMATCH", histories.get(0).getErrorCode());
        assertEquals(SettlementHistoryResult.MANUAL_REQUIRED, histories.get(0).getResult());
    }

    @Test
    @DisplayName("기존 지급 내역과 금액이 다르면 MANUAL_REQUIRED")
    void payout_existingTransferMismatch() {
        Settlement s = saveReady();
        paymentApi.overridePayout = 17_000L;

        useCase.payout(SETTLEMENT_ID, SettlementTrigger.MONTHLY_JOB, null, null);

        assertEquals(SettlementStatus.MANUAL_REQUIRED, s.getStatus());
    }

    @Test
    @DisplayName("판매자 없음(404-SELLER_NOT_FOUND)은 재시도 불가라 바로 MANUAL_REQUIRED")
    void payout_sellerNotFound() {
        Settlement s = saveReady();
        seller = () -> {
            throw new DomainException(new com.backend.global.exception.ErrorCode() {
                public int status() { return 404; }
                public String name() { return "SELLER_NOT_FOUND"; }
                public String msg() { return "판매자를 찾을 수 없습니다."; }
            });
        };

        useCase.payout(SETTLEMENT_ID, SettlementTrigger.MONTHLY_JOB, null, null);

        assertEquals(SettlementStatus.MANUAL_REQUIRED, s.getStatus());
        assertEquals("404-SELLER_NOT_FOUND", histories.get(0).getErrorCode());
    }

    @Test
    @DisplayName("3번 실패하면 MANUAL_REQUIRED, 재시도 일시 전에는 건너뛰고 실패 횟수에 세지 않는다")
    void payout_failThreeTimes() {
        Settlement s = saveReady();
        paymentApi.transferError = () -> new IllegalStateException("장애");

        useCase.payout(SETTLEMENT_ID, SettlementTrigger.MONTHLY_JOB, null, null);
        assertEquals(SettlementStatus.FAILED, s.getStatus());

        SettlementPayoutResult skipped = useCase.payout(SETTLEMENT_ID, SettlementTrigger.RETRY_SCHEDULER, null, null);
        assertFalse(skipped.processed());                       // 아직 다음 재시도 일시 전
        assertEquals(1, s.getRetryCount());
        assertEquals(SettlementHistoryResult.SKIPPED, histories.get(1).getResult());

        ReflectionTestUtils.setField(s, "nextRetryAt", LocalDateTime.now().minusSeconds(1));
        useCase.payout(SETTLEMENT_ID, SettlementTrigger.RETRY_SCHEDULER, null, null);
        assertEquals(2, s.getRetryCount());

        ReflectionTestUtils.setField(s, "nextRetryAt", LocalDateTime.now().minusSeconds(1));
        useCase.payout(SETTLEMENT_ID, SettlementTrigger.RETRY_SCHEDULER, null, null);
        assertEquals(SettlementStatus.MANUAL_REQUIRED, s.getStatus());
        assertEquals(3, s.getRetryCount());
        assertEquals(3, paymentApi.transferCalls);
    }

    @Test
    @DisplayName("지급 실패 후 다른 작업이 먼저 FAILED로 기록했으면 실패 횟수를 또 올리지 않고 SKIPPED 이력만 남긴다")
    void recordFailure_alreadyFailedByOther() {
        Settlement s = saveReady();
        LocalDateTime otherRetryAt = LocalDateTime.now().plusMinutes(10);
        paymentApi.transferError = () -> {
            // ①이 롤백된 사이 월 정산 Job이 같은 정산서를 먼저 실패로 기록한 상황
            ReflectionTestUtils.setField(s, "status", SettlementStatus.FAILED);
            ReflectionTestUtils.setField(s, "retryCount", 1);
            ReflectionTestUtils.setField(s, "nextRetryAt", otherRetryAt);
            return new IllegalStateException("DB 연결 끊김");
        };

        SettlementPayoutResult result = useCase.payout(SETTLEMENT_ID, SettlementTrigger.RETRY_SCHEDULER, null, null);

        assertFalse(result.processed());
        assertEquals(SettlementStatus.FAILED, s.getStatus());
        assertEquals(1, s.getRetryCount());                  // 이중으로 늘지 않음
        assertEquals(otherRetryAt, s.getNextRetryAt());      // 재시도 시각도 그대로
        SettlementProcessHistory h = histories.get(0);
        assertEquals(SettlementHistoryResult.SKIPPED, h.getResult());
        assertEquals("IllegalStateException", h.getErrorCode());
    }

    @Test
    @DisplayName("지급 실패 후 다른 작업이 먼저 MANUAL_REQUIRED로 바꿨으면 자동 재시도(FAILED)로 되돌리지 않는다")
    void recordFailure_alreadyManualByOther() {
        Settlement s = saveReady();
        paymentApi.transferError = () -> {
            ReflectionTestUtils.setField(s, "status", SettlementStatus.MANUAL_REQUIRED);
            ReflectionTestUtils.setField(s, "retryCount", 1);
            return new IllegalStateException("락 경합");
        };

        useCase.payout(SETTLEMENT_ID, SettlementTrigger.RETRY_SCHEDULER, null, null);

        assertEquals(SettlementStatus.MANUAL_REQUIRED, s.getStatus());
        assertEquals(1, s.getRetryCount());
        assertEquals(SettlementHistoryResult.SKIPPED, histories.get(0).getResult());
    }

    @Test
    @DisplayName("PAID·HOLD 정산서는 건너뛰고 결제 쪽을 호출하지 않는다")
    void payout_notPayable() {
        Settlement s = saveReady();
        s.hold("제재");

        SettlementPayoutResult result = useCase.payout(SETTLEMENT_ID, SettlementTrigger.RETRY_SCHEDULER, null, null);

        assertFalse(result.processed());
        assertEquals(SettlementStatus.HOLD, s.getStatus());
        assertEquals(0, paymentApi.transferCalls);
    }

    @Test
    @DisplayName("관리자 재처리: MANUAL_REQUIRED만, 실패하면 MANUAL_REQUIRED 유지, 성공하면 PAID, 사유는 이력에")
    void retryManually() {
        Settlement s = saveReady();
        DomainException notManual = assertThrows(DomainException.class,
                () -> useCase.retryManually(SETTLEMENT_ID, "확인 완료"));
        assertEquals("409-SETTLEMENT_STATE_INVALID", notManual.getResultCode());

        s.markManualRequired("금액 불일치");
        paymentApi.transferError = () -> new IllegalStateException("장애");
        useCase.retryManually(SETTLEMENT_ID, "지갑 확인 후 재처리");
        assertEquals(SettlementStatus.MANUAL_REQUIRED, s.getStatus());

        paymentApi.transferError = null;
        SettlementPayoutResult result = useCase.retryManually(SETTLEMENT_ID, "원인 수정 후 재처리");
        assertTrue(result.isPaid());
        SettlementProcessHistory last = histories.get(histories.size() - 1);
        assertEquals(SettlementTrigger.ADMIN, last.getTriggerType());
        assertEquals("원인 수정 후 재처리", last.getAdminReason());
        assertEquals(3, last.getAttemptNo());
    }

    @Test
    @DisplayName("없는 정산서 재처리는 404")
    void retryManually_notFound() {
        DomainException e = assertThrows(DomainException.class, () -> useCase.retryManually(999L, "사유"));
        assertEquals("404-SETTLEMENT_NOT_FOUND", e.getResultCode());
    }

    /** 주문 2건(10,000 + 10,000)이 묶인 READY 정산서: 지급 18,000 / 수수료 2,000 */
    private Settlement saveReady() {
        Settlement s = Settlement.create(SELLER_ID, "2026-09", null);
        s.addCandidate(candidate(101L, 1001L));
        s.addCandidate(candidate(102L, 1002L));
        ReflectionTestUtils.setField(s, "id", SETTLEMENT_ID);
        s.assignIdempotencyKey();
        table.put(SETTLEMENT_ID, s);
        totals = new SettlementCandidateTotalsRow(20_000L, 2_000L, 18_000L, 2L);
        return s;
    }

    private static SettlementCandidate candidate(Long orderId, Long orderItemId) {
        return SettlementCandidate.create(orderId, orderItemId, SELLER_ID, 10_000L, 10,
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

    /** 결제 컨텍스트 가짜의 상태. 호출 횟수와 마지막 명령을 기록한다 */
    private static class FakePayment {
        Supplier<RuntimeException> transferError;
        Long overridePayout;
        SettlementTransferCommand lastCommand;
        int transferCalls;

        TransferResultDto transfer(SettlementTransferCommand command) {
            transferCalls++;
            lastCommand = command;
            if (transferError != null) throw transferError.get();
            long payout = overridePayout != null ? overridePayout : command.payoutAmount();
            return new TransferResultDto(command.idempotencyKey(), 500L, payout, command.feeAmount(),
                    LocalDateTime.now());
        }
    }

    /** PaymentApi를 프록시로 만들어 transfer·findTransfer만 FakePayment로 넘긴다 */
    private PaymentApi fakePaymentApi() {
        return fake(PaymentApi.class, Map.of(
                "transfer", args -> paymentApi.transfer((SettlementTransferCommand) args[0]),
                "findTransfer", args -> Optional.empty()));
    }
}