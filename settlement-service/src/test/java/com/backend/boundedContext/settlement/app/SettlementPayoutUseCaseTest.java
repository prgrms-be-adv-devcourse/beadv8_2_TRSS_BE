package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import com.backend.boundedContext.settlement.domain.SettlementHistoryResult;
import com.backend.boundedContext.settlement.domain.SettlementHistoryStage;
import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import com.backend.boundedContext.settlement.domain.SettlementProcessHistory;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.domain.judge.LedgerMismatchRule;
import com.backend.boundedContext.settlement.in.dto.SettlementPayoutResult;
import com.backend.boundedContext.settlement.out.SettlementCandidateRepository;
import com.backend.boundedContext.settlement.out.SettlementCandidateTotalsRow;
import com.backend.boundedContext.settlement.out.SettlementOrderAmountRow;
import com.backend.boundedContext.settlement.out.SettlementMemberPort;
import com.backend.boundedContext.settlement.out.SettlementPaymentPort;
import com.backend.boundedContext.settlement.out.SettlementProcessHistoryRepository;
import com.backend.boundedContext.settlement.out.SettlementRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.payment.dto.SettlementTransferCommand;
import com.backend.shared.payment.dto.TransferResultDto;
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
 * 지급 3구간(① PROCESSING 선점 → ② 트랜잭션 밖 지급 요청 → ③ 결과 기록)과 결과 재확인을 DB 없이 검증한다.
 * 결제가 거절한 4xx는 실패로, 5xx·응답 없음 같은 결과 모름은 PROCESSING으로 둔다(3차 코드리뷰 변경 제안 2).
 * Repository·SettlementPaymentPort·SettlementMemberPort는 메모리 가짜이고, 트랜잭션은 ResourcelessTransactionManager로 흉내 낸다.
 * Port 가짜는 동적 프록시로 쓰는 메서드만 흉내 내므로, Port에 메서드가 더 생겨도 이 테스트는 고칠 필요가 없다.
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
    private boolean ledgerMismatch;   // 이 판매자에게 풀리지 않은 원장 재대조 불일치가 있는지
    private final SettlementPayoutUseCase useCase;

    SettlementPayoutUseCaseTest() {
        SettlementRepository settlementRepository = fake(SettlementRepository.class, Map.of(
                "findById", args -> Optional.ofNullable(table.get((Long) args[0])),
                "findWithLockById", args -> Optional.ofNullable(table.get((Long) args[0])),
                "existsUnresolvedLedgerMismatchBySellerId", args -> ledgerMismatch));
        SettlementCandidateRepository candidateRepository = fake(SettlementCandidateRepository.class, Map.of(
                "sumIncludedTotals", args -> totals,
                "sumSalePriceByOrder", args -> List.of(new SettlementOrderAmountRow(101L, 10_000L),
                        new SettlementOrderAmountRow(102L, 10_000L))));
        SettlementProcessHistoryRepository historyRepository = fake(SettlementProcessHistoryRepository.class,
                Map.of("save", args -> {
                    histories.add((SettlementProcessHistory) args[0]);
                    return args[0];
                }));
        SettlementMemberPort settlementMemberPort = fake(SettlementMemberPort.class, Map.of("getSeller", args -> seller.get()));
        useCase = new SettlementPayoutUseCase(
                new SettlementSupport(settlementRepository, candidateRepository, historyRepository, settlementMemberPort),
                candidateRepository, historyRepository, fakePaymentPort(), settlementMemberPort,
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
        paymentApi.transferError = SettlementPayoutUseCaseTest::lockConflict;

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
    @DisplayName("결제가 거절한 재시도 가능 오류(4xx): 별도 트랜잭션에서 FAILED + 다음 재시도 일시 + 실패 이력")
    void payout_transientFailure() {
        Settlement s = saveReady();
        paymentApi.transferError = SettlementPayoutUseCaseTest::lockConflict;

        SettlementPayoutResult result = useCase.payout(SETTLEMENT_ID, SettlementTrigger.MONTHLY_JOB, null, null);

        assertTrue(result.processed());
        assertEquals(SettlementStatus.FAILED, s.getStatus());
        assertEquals(1, s.getRetryCount());
        assertNotNull(s.getNextRetryAt());
        SettlementProcessHistory h = histories.get(0);
        assertEquals(SettlementHistoryResult.FAILED, h.getResult());
        assertEquals("409-LOCK_CONFLICT", h.getErrorCode());
        assertEquals("잠시 후 다시 시도해 주세요.", h.getFailReason());
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
        paymentApi.transferError = SettlementPayoutUseCaseTest::lockConflict;

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
            return lockConflict();
        };

        SettlementPayoutResult result = useCase.payout(SETTLEMENT_ID, SettlementTrigger.RETRY_SCHEDULER, null, null);

        assertFalse(result.processed());
        assertEquals(SettlementStatus.FAILED, s.getStatus());
        assertEquals(1, s.getRetryCount());                  // 이중으로 늘지 않음
        assertEquals(otherRetryAt, s.getNextRetryAt());      // 재시도 시각도 그대로
        SettlementProcessHistory h = histories.get(0);
        assertEquals(SettlementHistoryResult.SKIPPED, h.getResult());
        assertEquals("409-LOCK_CONFLICT", h.getErrorCode());
    }

    @Test
    @DisplayName("지급 실패 후 다른 작업이 먼저 MANUAL_REQUIRED로 바꿨으면 자동 재시도(FAILED)로 되돌리지 않는다")
    void recordFailure_alreadyManualByOther() {
        Settlement s = saveReady();
        paymentApi.transferError = () -> {
            ReflectionTestUtils.setField(s, "status", SettlementStatus.MANUAL_REQUIRED);
            ReflectionTestUtils.setField(s, "retryCount", 1);
            return lockConflict();
        };

        useCase.payout(SETTLEMENT_ID, SettlementTrigger.RETRY_SCHEDULER, null, null);

        assertEquals(SettlementStatus.MANUAL_REQUIRED, s.getStatus());
        assertEquals(1, s.getRetryCount());
        assertEquals(SettlementHistoryResult.SKIPPED, histories.get(0).getResult());
    }

    @Test
    @DisplayName("지급 직전 판매자가 SANCTIONED면 결제 쪽을 호출하지 않고 HOLD + 보류 이력(지급한 경로로 기록)")
    void payout_sellerSanctioned() {
        Settlement s = saveReady();
        seller = () -> new SellerDto(SELLER_ID, SELLER_MEMBER_ID, "팔레트상점", SellerStatus.SANCTIONED);

        SettlementPayoutResult result = useCase.payout(SETTLEMENT_ID, SettlementTrigger.MONTHLY_JOB, 900L, null);

        assertFalse(result.processed());                     // 지급하지 않았으므로 실패 횟수에도 세지 않는다
        assertEquals(SettlementStatus.HOLD, s.getStatus());
        assertEquals(SettlementPayoutUseCase.SANCTIONED_HOLD_REASON, s.getHoldReason());
        assertEquals(0, paymentApi.transferCalls);
        SettlementProcessHistory h = histories.get(0);
        assertEquals(SettlementHistoryStage.HOLD, h.getStage());
        assertEquals(SettlementTrigger.MONTHLY_JOB, h.getTriggerType());
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
        paymentApi.transferError = SettlementPayoutUseCaseTest::lockConflict;
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
    @DisplayName("지급 직전 판매자의 원장 재대조 불일치가 풀리지 않았으면 결제 쪽을 호출하지 않고 원장 불일치 사유로 HOLD")
    void payout_ledgerMismatchUnresolved() {
        Settlement s = saveReady();
        ledgerMismatch = true;

        SettlementPayoutResult result = useCase.payout(SETTLEMENT_ID, SettlementTrigger.RETRY_SCHEDULER, null, null);

        assertFalse(result.processed());
        assertEquals(SettlementStatus.HOLD, s.getStatus());
        assertEquals(LedgerMismatchRule.REASON, s.getHoldReason());
        assertEquals(0, paymentApi.transferCalls);
        SettlementProcessHistory h = histories.get(0);
        assertEquals(SettlementHistoryStage.HOLD, h.getStage());
        assertEquals(SettlementTrigger.RETRY_SCHEDULER, h.getTriggerType());
    }

    @Test
    @DisplayName("관리자 재처리: 판매자가 SANCTIONED면 409 대신 HOLD 결과(보류 사유 포함)를 돌려주고 결제 쪽은 호출하지 않는다")
    void retryManually_sellerSanctioned() {
        Settlement s = saveReady();
        s.markManualRequired("금액 불일치");
        seller = () -> new SellerDto(SELLER_ID, SELLER_MEMBER_ID, "팔레트상점", SellerStatus.SANCTIONED);

        SettlementPayoutResult result = useCase.retryManually(SETTLEMENT_ID, "확인 후 재처리");

        assertFalse(result.processed());
        assertEquals(SettlementStatus.HOLD, result.status());
        assertEquals(SettlementPayoutUseCase.SANCTIONED_HOLD_REASON, result.holdReason());
        assertEquals(SettlementStatus.HOLD, s.getStatus());
        assertEquals(0, paymentApi.transferCalls);
    }

    @Test
    @DisplayName("없는 정산서 재처리는 404")
    void retryManually_notFound() {
        DomainException e = assertThrows(DomainException.class, () -> useCase.retryManually(999L, "사유"));
        assertEquals("404-SETTLEMENT_NOT_FOUND", e.getResultCode());
    }

    @Test
    @DisplayName("결과 모름(응답 없음 등): PROCESSING으로 남고 결과 모름 이력을 남기며, 보류·재시도 대상에서 빠진다")
    void payout_resultUnknown_staysProcessing() {
        Settlement s = saveReady();
        paymentApi.transferError = () -> new IllegalStateException("응답 시간 초과");

        SettlementPayoutResult result = useCase.payout(SETTLEMENT_ID, SettlementTrigger.MONTHLY_JOB, 900L, null);

        assertTrue(result.processed());
        assertEquals(SettlementStatus.PROCESSING, s.getStatus());
        assertNotNull(s.getProcessingStartedAt());
        assertEquals(0, s.getRetryCount());                          // 실패로 세지 않는다
        assertEquals(SettlementHistoryResult.PROCESSING, histories.get(0).getResult());
        assertThrows(DomainException.class, () -> s.hold("제재"));    // 보류(관리자·제재) 대상이 아니다
        assertFalse(s.isPayable(false, LocalDateTime.now()));         // 재시도·Job 지급 대상이 아니다

        SettlementPayoutResult again = useCase.payout(SETTLEMENT_ID, SettlementTrigger.RETRY_SCHEDULER, null, null);
        assertFalse(again.processed());
        assertEquals(1, paymentApi.transferCalls);                   // 결과를 모르는 동안 다시 요청하지 않는다
    }

    @Test
    @DisplayName("결제 5xx도 결과 모름으로 PROCESSING에 둔다")
    void payout_payment5xx_staysProcessing() {
        Settlement s = saveReady();
        paymentApi.transferError = () -> new DomainException(errorCode(503, "PAYMENT_UNAVAILABLE", "결제 서비스 응답 없음"));

        useCase.payout(SETTLEMENT_ID, SettlementTrigger.MONTHLY_JOB, null, null);

        assertEquals(SettlementStatus.PROCESSING, s.getStatus());
    }

    @Test
    @DisplayName("연결 전 임시 구현(UnsupportedOperationException)은 요청을 보내지 않으므로 결과 모름이 아니라 실패로 기록한다")
    void payout_notConnectedStub_failed() {
        Settlement s = saveReady();
        paymentApi.transferError = () -> new UnsupportedOperationException("결제 연결 전 임시 구현");

        useCase.payout(SETTLEMENT_ID, SettlementTrigger.MONTHLY_JOB, null, null);

        assertEquals(SettlementStatus.FAILED, s.getStatus());
    }

    @Test
    @DisplayName("결과 재확인: 결제 원장에 같은 금액의 지급 기록이 있으면 PAID")
    void reconcile_ledgerFound_paid() {
        Settlement s = saveProcessing();
        paymentApi.ledger = new TransferResultDto("SETTLEMENT-31", 501L, 18_000L, 2_000L, LocalDateTime.now());

        SettlementPayoutResult result = useCase.reconcileProcessing(SETTLEMENT_ID);

        assertTrue(result.isPaid());
        assertEquals(SettlementStatus.PAID, s.getStatus());
        assertEquals(501L, s.getWalletTxId());
        assertEquals(SettlementHistoryResult.SUCCEEDED, histories.get(0).getResult());
    }

    @Test
    @DisplayName("결과 재확인: 지급 기록이 없으면 FAILED(재시도 대상), 다음 재시도는 같은 멱등 키로 요청한다")
    void reconcile_ledgerMissing_failed() {
        Settlement s = saveProcessing();
        paymentApi.ledger = null;

        useCase.reconcileProcessing(SETTLEMENT_ID);

        assertEquals(SettlementStatus.FAILED, s.getStatus());
        assertEquals(1, s.getRetryCount());
        assertNotNull(s.getNextRetryAt());
        assertEquals("SETTLEMENT-31", s.getIdempotencyKey());
    }

    @Test
    @DisplayName("결과 재확인: 원장 금액이 정산서와 다르면 MANUAL_REQUIRED")
    void reconcile_ledgerAmountDiffers_manual() {
        Settlement s = saveProcessing();
        paymentApi.ledger = new TransferResultDto("SETTLEMENT-31", 501L, 17_000L, 2_000L, LocalDateTime.now());

        useCase.reconcileProcessing(SETTLEMENT_ID);

        assertEquals(SettlementStatus.MANUAL_REQUIRED, s.getStatus());
    }

    @Test
    @DisplayName("결과 재확인: PROCESSING이 아니면(응답이 먼저 와서 확정됨) 아무것도 바꾸지 않는다")
    void reconcile_notProcessing_skipped() {
        Settlement s = saveReady();

        SettlementPayoutResult result = useCase.reconcileProcessing(SETTLEMENT_ID);

        assertFalse(result.processed());
        assertEquals(SettlementStatus.READY, s.getStatus());
        assertEquals(0, paymentApi.findCalls);
    }

    @Test
    @DisplayName("지급 응답을 받기 전에 재확인이 먼저 PAID로 확정했으면, 늦게 온 응답은 SKIPPED 이력만 남긴다")
    void complete_afterReconcile_skipped() {
        Settlement s = saveReady();
        paymentApi.beforeReturn = () -> {
            // 응답이 늦어지는 사이 재확인 스케줄러가 같은 키의 원장을 찾아 PAID로 확정한 상황
            s.markPaid(501L, LocalDateTime.now());
        };

        SettlementPayoutResult result = useCase.payout(SETTLEMENT_ID, SettlementTrigger.MONTHLY_JOB, null, null);

        assertFalse(result.processed());
        assertEquals(SettlementStatus.PAID, s.getStatus());
        assertEquals(SettlementHistoryResult.SKIPPED, histories.get(0).getResult());
    }

    @Test
    @DisplayName("관리자 재처리의 결과를 모른 채 재확인에서 지급 기록이 없으면, 자동 재시도(FAILED)가 아니라 MANUAL_REQUIRED로 둔다")
    void reconcile_manualRetryLedgerMissing_staysManual() {
        Settlement s = saveReady();
        s.markManualRequired("판매자 지갑 없음");                     // 재시도 불가 사유로 1회 만에 MANUAL_REQUIRED
        paymentApi.transferError = () -> new IllegalStateException("응답 시간 초과");
        useCase.retryManually(SETTLEMENT_ID, "지갑 생성 확인 후 재처리");
        assertEquals(SettlementStatus.PROCESSING, s.getStatus());
        assertTrue(s.isProcessingManual());

        paymentApi.ledger = null;
        useCase.reconcileProcessing(SETTLEMENT_ID);

        assertEquals(SettlementStatus.MANUAL_REQUIRED, s.getStatus());
        assertEquals(2, s.getRetryCount());                           // 3회 미만이어도 FAILED로 가지 않는다
        assertFalse(s.isPayable(false, LocalDateTime.now().plusDays(1)));   // 재시도 스케줄러 대상이 아니다
        assertFalse(s.isProcessingManual());
        assertEquals(SettlementHistoryResult.MANUAL_REQUIRED, histories.get(histories.size() - 1).getResult());
    }

    @Test
    @DisplayName("자동 지급의 PROCESSING은 관리자 재처리 표시가 없어, 재확인에서 지급 기록이 없으면 FAILED(재시도 대상)")
    void payout_autoProcessing_notMarkedManual() {
        Settlement s = saveReady();
        paymentApi.transferError = () -> new IllegalStateException("응답 시간 초과");

        useCase.payout(SETTLEMENT_ID, SettlementTrigger.RETRY_SCHEDULER, null, null);

        assertEquals(SettlementStatus.PROCESSING, s.getStatus());
        assertFalse(s.isProcessingManual());
    }

    @Test
    @DisplayName("지급 응답이 늦어 재확인이 먼저 FAILED로 바꿨어도, 늦게 온 성공 응답으로 PAID로 확정한다")
    void complete_afterReconcileFailed_paid() {
        Settlement s = saveReady();
        paymentApi.beforeReturn = () -> s.markFailed("결과 확인: 결제 원장에 지급 기록 없음", LocalDateTime.now(), 3, 10);

        SettlementPayoutResult result = useCase.payout(SETTLEMENT_ID, SettlementTrigger.MONTHLY_JOB, null, null);

        assertTrue(result.isPaid());
        assertEquals(SettlementStatus.PAID, s.getStatus());
        assertEquals(500L, s.getWalletTxId());
        assertEquals(SettlementHistoryResult.SUCCEEDED, histories.get(histories.size() - 1).getResult());
    }

    @Test
    @DisplayName("재확인이 먼저 MANUAL_REQUIRED로 바꿨어도(재시도 소진) 늦게 온 성공 응답으로 PAID로 확정한다")
    void complete_afterReconcileManual_paid() {
        Settlement s = saveReady();
        paymentApi.beforeReturn = () -> s.markManualRequired("결과 확인: 결제 원장에 지급 기록 없음");

        SettlementPayoutResult result = useCase.payout(SETTLEMENT_ID, SettlementTrigger.MONTHLY_JOB, null, null);

        assertTrue(result.isPaid());
        assertEquals(SettlementStatus.PAID, s.getStatus());
    }

    @Test
    @DisplayName("늦게 온 성공 응답이어도 금액이 정산서와 다르면 PAID로 바꾸지 않고 MANUAL_REQUIRED")
    void complete_afterReconcileFailed_amountDiffers_manual() {
        Settlement s = saveReady();
        paymentApi.beforeReturn = () -> s.markFailed("결과 확인: 결제 원장에 지급 기록 없음", LocalDateTime.now(), 3, 10);
        paymentApi.overridePayout = 17_000L;

        useCase.payout(SETTLEMENT_ID, SettlementTrigger.MONTHLY_JOB, null, null);

        assertEquals(SettlementStatus.MANUAL_REQUIRED, s.getStatus());
    }

    @Test
    @DisplayName("지급은 됐는데 그사이 관리자가 보류(HOLD)했으면 상태를 바꾸지 않고 확인 필요 이력을 남긴다")
    void complete_afterHold_keepsHoldAndRecords() {
        Settlement s = saveReady();
        paymentApi.beforeReturn = () -> {
            s.markFailed("결과 확인: 결제 원장에 지급 기록 없음", LocalDateTime.now(), 3, 10);
            s.hold("관리자 보류");
        };

        SettlementPayoutResult result = useCase.payout(SETTLEMENT_ID, SettlementTrigger.MONTHLY_JOB, null, null);

        assertFalse(result.processed());
        assertEquals(SettlementStatus.HOLD, s.getStatus());
        SettlementProcessHistory last = histories.get(histories.size() - 1);
        assertEquals(SettlementHistoryResult.SKIPPED, last.getResult());
        assertTrue(last.getFailReason().contains("확인 필요"));
    }

    /** 재시도할 수 있는 4xx 거절(결제가 판단을 마쳐 돈이 움직이지 않음) */
    private static DomainException lockConflict() {
        return new DomainException(errorCode(409, "LOCK_CONFLICT", "잠시 후 다시 시도해 주세요."));
    }

    private static com.backend.global.exception.ErrorCode errorCode(int status, String name, String msg) {
        return new com.backend.global.exception.ErrorCode() {
            public int status() { return status; }
            public String name() { return name; }
            public String msg() { return msg; }
        };
    }

    /** 지급을 요청했는데 결과를 모르는 PROCESSING 정산서 (5분 넘게 지남) */
    private Settlement saveProcessing() {
        Settlement s = saveReady();
        s.markProcessing(LocalDateTime.now().minusMinutes(10));
        return s;
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
        Runnable beforeReturn;
        Long overridePayout;
        TransferResultDto ledger;
        SettlementTransferCommand lastCommand;
        int transferCalls;
        int findCalls;

        TransferResultDto transfer(SettlementTransferCommand command) {
            transferCalls++;
            lastCommand = command;
            if (transferError != null) throw transferError.get();
            if (beforeReturn != null) beforeReturn.run();
            long payout = overridePayout != null ? overridePayout : command.payoutAmount();
            return new TransferResultDto(command.idempotencyKey(), 500L, payout, command.feeAmount(),
                    LocalDateTime.now());
        }
    }

    /** SettlementPaymentPort를 프록시로 만들어 transfer를 FakePayment로 넘긴다 */
    private SettlementPaymentPort fakePaymentPort() {
        return fake(SettlementPaymentPort.class, Map.of(
                "transfer", args -> paymentApi.transfer((SettlementTransferCommand) args[0]),
                "findTransfer", args -> {
                    paymentApi.findCalls++;
                    return Optional.ofNullable(paymentApi.ledger);
                }));
    }
}