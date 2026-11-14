package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementErrorCode;
import com.backend.boundedContext.settlement.domain.SettlementHistoryResult;
import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import com.backend.boundedContext.settlement.domain.SettlementProcessHistory;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.domain.judge.LedgerMismatchRule;
import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.in.dto.SettlementPayoutResult;
import com.backend.boundedContext.settlement.out.SettlementCandidateRepository;
import com.backend.boundedContext.settlement.out.SettlementCandidateTotalsRow;
import com.backend.boundedContext.settlement.out.SettlementMemberPort;
import com.backend.boundedContext.settlement.out.SettlementPaymentPort;
import com.backend.boundedContext.settlement.out.SettlementProcessHistoryRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.payment.dto.SettledOrderAmountDto;
import com.backend.shared.payment.dto.SettlementTransferCommand;
import com.backend.shared.payment.dto.TransferResultDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 정산서 1건 지급 (판매자 1명 = 정산서 1건 = 처리 단위 1개)
 *
 * 결제는 다른 서비스(HTTP)라 "같은 트랜잭션에서 함께 롤백"이 성립하지 않는다. 그래서 지급을 세 구간으로 나눈다(3차 코드리뷰 변경 제안 2).
 *
 * ⓪ 이전에 지급을 시도한 정산서(FAILED·MANUAL_REQUIRED)는 결제 원장부터 확인(잠금 밖). 이미 지급됐으면 다시 요청하지 않고 확정
 * ① 선점 (짧은 트랜잭션)
 *    판매자 조회(잠금 밖) → 정산서 잠금 → 지급 가능한 상태인지 → 판매자 제재면 HOLD로 끝 → 원장 재대조 불일치가 남았으면 HOLD로 끝
 *    → 정산서 합계 = 후보 합계 확인 → PROCESSING(선점 시각, 관리자 재처리 여부 기록)으로 커밋
 * ② 지급 요청 (트랜잭션·행 잠금 밖)
 *    SettlementPaymentPort.transfer(멱등 키 SETTLEMENT-{id})
 * ③ 결과 기록 (짧은 트랜잭션)
 *    성공: PROCESSING이면 금액 확인 후 PAID. 응답이 늦어 재확인이 먼저 FAILED·MANUAL_REQUIRED로 바꿨어도 PAID로 확정
 *          (그사이 HOLD가 됐어도 금액이 같으면 PAID로 맞추고 보류 사유는 이력에)
 *    결제가 확실히 거절(4xx): FAILED(재시도 불가 코드면 MANUAL_REQUIRED)
 *    결과 모름(응답 없음, 5xx 등): PROCESSING으로 둔다 → 재확인 스케줄러가 findTransfer로 확정(reconcileProcessing)
 *
 * ①에서 난 예외(합계 불일치, 판매자 조회 실패 등)는 돈이 움직이기 전이므로 지금처럼 별도 트랜잭션에서 실패로 기록한다.
 * PROCESSING은 지급 가능 상태가 아니므로 관리자 보류, 제재 보류, 재시도 대상에서 자동으로 빠진다.
 * 모든 트랜잭션은 TransactionTemplate(REQUIRES_NEW)이라 호출한 쪽(배치·스케줄러·관리자)에 트랜잭션이 있어도 따로 커밋된다.
 */
@Slf4j
@Component
public class SettlementPayoutUseCase {

    /** 지급 직전 판매자가 제재 상태일 때 정산서에 남기는 보류 사유 */
    static final String SANCTIONED_HOLD_REASON = "판매자 제재 상태라 지급 직전에 보류(제재 이벤트 수신 전 지급 방지)";

    private final SettlementSupport settlementSupport;
    private final SettlementCandidateRepository settlementCandidateRepository;
    private final SettlementProcessHistoryRepository settlementProcessHistoryRepository;
    private final SettlementPaymentPort settlementPaymentPort;
    private final SettlementMemberPort settlementMemberPort;
    // 10/14 리뷰 반영:정책 빈을 생성자로 주입받아 getMaxAttempts()·getRetryIntervalMinutes()로 꺼낸다(값은 인스턴스).
    // isNonRetryable 같은 규칙은 값에 기대지 않으므로 그대로 SettlementPolicy.isNonRetryable(...)로 부른다(규칙은 정적)
    private final SettlementPolicy settlementPolicy;
    private final TransactionTemplate newTransaction;

    public SettlementPayoutUseCase(SettlementSupport settlementSupport,
                                   SettlementCandidateRepository settlementCandidateRepository,
                                   SettlementProcessHistoryRepository settlementProcessHistoryRepository,
                                   SettlementPaymentPort settlementPaymentPort,
                                   SettlementMemberPort settlementMemberPort,
                                   SettlementPolicy settlementPolicy, // 10/14 리뷰 반영: 정책 빈 생성자 주입
                                   PlatformTransactionManager transactionManager) {
        this.settlementSupport = settlementSupport;
        this.settlementCandidateRepository = settlementCandidateRepository;
        this.settlementProcessHistoryRepository = settlementProcessHistoryRepository;
        this.settlementPaymentPort = settlementPaymentPort;
        this.settlementMemberPort = settlementMemberPort;
        this.settlementPolicy = settlementPolicy;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** ①의 결과: 건너뛰었으면 skipped, 지급을 요청할 수 있으면 command와 이번 시도 번호 */
    private record Prepared(SettlementPayoutResult skipped, SettlementTransferCommand command, int attemptNo) {
        static Prepared skip(SettlementPayoutResult result) {
            return new Prepared(result, null, 0);
        }
    }

    /**
     * 월 정산 Job·재시도 스케줄러·관리자 재처리가 모두 이 메서드를 쓴다(같은 지급 로직)
     * 지급 가능한 상태가 아니면 건너뛰고(processed=false), 건너뛴 경우는 실패 횟수에 세지 않는다
     *
     * 관리자 재처리 = 월 정산 Job 밖에서 관리자가 정산서 1건을 지급(ADMIN, batchExecutionId 없음)
     * → MANUAL_REQUIRED만 지급하고, 실패하면 바로 MANUAL_REQUIRED
     * 관리자가 수동 실행한 월 정산 Job(ADMIN, batchExecutionId 있음)은 이력에만 ADMIN으로 남고
     * 지급 기준은 스케줄러가 실행한 Job과 같다(READY·재시도 일시가 지난 FAILED, 일시 오류는 FAILED)
     */
    public SettlementPayoutResult payout(Long settlementId, SettlementTrigger trigger, Long batchExecutionId,
                                         String adminReason) {
        boolean manualRetry = trigger == SettlementTrigger.ADMIN && batchExecutionId == null;

        // 이전에 지급을 시도한 정산서는 다시 요청하기 전에 결제 원장부터 본다(잠금 밖)
        Settlement current = settlementSupport.getSettlement(settlementId);
        Optional<SettlementPayoutResult> confirmed = confirmEarlierTransfer(current, trigger, manualRetry,
                batchExecutionId, adminReason);
        if (confirmed.isPresent()) return confirmed.get();

        // ① 선점. 판매자 조회(HTTP)는 행 잠금 전에 끝낸다
        Prepared prepared;
        try {
            SellerDto seller = settlementMemberPort.getSeller(current.getSellerId());
            prepared = newTransaction.execute(status ->
                    prepare(settlementId, seller, trigger, manualRetry, batchExecutionId, adminReason));
        } catch (RuntimeException e) {
            // 돈이 움직이기 전의 실패(합계 불일치, 판매자 조회 실패 등)
            logFailure(settlementId, trigger, e);
            return newTransaction.execute(status ->
                    recordFailure(settlementId, trigger, manualRetry, batchExecutionId, adminReason, e, false));
        }
        if (prepared.skipped() != null) return prepared.skipped();
        final int attemptNo = prepared.attemptNo();

        // ② 지급 요청: 트랜잭션·행 잠금 밖
        final TransferResultDto transferred;
        try {
            transferred = settlementPaymentPort.transfer(prepared.command());
        } catch (RuntimeException e) {
            logFailure(settlementId, trigger, e);
            if (isResultUnknown(e)) {
                return newTransaction.execute(status -> recordUnknown(settlementId, attemptNo, trigger,
                        batchExecutionId, adminReason, e));
            }
            return newTransaction.execute(status ->
                    recordFailure(settlementId, trigger, manualRetry, batchExecutionId, adminReason, e, true));
        }

        // ③ 결과 기록
        return newTransaction.execute(status -> complete(settlementId, attemptNo, transferred, trigger,
                batchExecutionId, adminReason));
    }

    /**
     * 이전 시도가 이미 지급됐는지 결제 원장으로 확인한다(FAILED·MANUAL_REQUIRED 중 지금 지급할 수 있는 정산서만)
     * 재확인이 결제 커밋 전에 돌아 FAILED가 됐고 그 요청의 응답도 받지 못했다면, 실제로는 지급된 정산서다.
     * 이 확인 없이 ①로 가면 그사이 제재·원장 불일치가 생긴 경우 transfer를 부르지 않고 HOLD로 끝나, 지급된 정산서가 HOLD에 남는다.
     * 지급 기록이 있으면 금액이 같을 때 PAID, 다르면 MANUAL_REQUIRED로 확정하고 다시 요청하지 않는다.
     * 기록이 없거나 원장 조회가 실패하면(연결 전 임시 구현 포함) 비어 있는 값을 돌려줘 지금처럼 지급을 진행한다(같은 멱등 키라 안전).
     */
    private Optional<SettlementPayoutResult> confirmEarlierTransfer(Settlement current, SettlementTrigger trigger,
                                                                    boolean manualRetry, Long batchExecutionId,
                                                                    String adminReason) {
        boolean triedBefore = current.getStatus() == SettlementStatus.FAILED
                || current.getStatus() == SettlementStatus.MANUAL_REQUIRED;
        if (!triedBefore || !current.isPayable(manualRetry, LocalDateTime.now())) return Optional.empty();

        Optional<TransferResultDto> ledger;
        try {
            ledger = settlementPaymentPort.findTransfer(current.getIdempotencyKey());
        } catch (RuntimeException e) {
            log.warn("지급 전 결제 원장 확인 실패, 그대로 지급을 진행 settlementId={} [{}]", current.getId(),
                    errorCodeOf(e));
            return Optional.empty();
        }
        if (ledger.isEmpty()) return Optional.empty();

        TransferResultDto transferred = ledger.get();
        Long settlementId = current.getId();
        return Optional.of(newTransaction.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            Settlement settlement = settlementSupport.getSettlementWithLock(settlementId);
            if (settlement.getStatus() != SettlementStatus.FAILED
                    && settlement.getStatus() != SettlementStatus.MANUAL_REQUIRED) {
                return SettlementPayoutResult.skipped(settlement);   // 원장을 보는 사이 다른 작업이 먼저 바꿨다
            }
            int attemptNo = settlement.nextAttemptNo();
            if (settlement.matchesTransfer(transferred.payoutAmount(), transferred.feeAmount())) {
                settlement.markPaid(transferred.walletTransactionId(), now);
                settlementProcessHistoryRepository.save(SettlementProcessHistory.payout(settlementId, attemptNo,
                        trigger, SettlementHistoryResult.SUCCEEDED, null,
                        "이전 지급 요청이 결제에 반영된 것을 원장에서 확인해 PAID(다시 요청하지 않음)", adminReason,
                        batchExecutionId, now));
                log.info("정산 지급 전 원장 확인: 이전 요청이 이미 지급됨 settlementId={} trigger={}", settlementId, trigger);
            } else {
                DomainException mismatch = new DomainException(SettlementErrorCode.SETTLEMENT_AMOUNT_MISMATCH);
                settlement.markManualRequired(mismatch.getMsg());
                settlementProcessHistoryRepository.save(SettlementProcessHistory.payout(settlementId, attemptNo,
                        trigger, SettlementHistoryResult.MANUAL_REQUIRED, mismatch.getResultCode(),
                        "지급 전 원장 확인: 이전 지급 금액이 정산서와 다름", adminReason, batchExecutionId, now));
                log.error("정산 지급 전 원장 확인: 이전 지급 금액이 정산서와 다름 settlementId={}", settlementId);
            }
            return SettlementPayoutResult.of(settlement, attemptNo, true);
        }));
    }

    /**
     * 결제를 호출했는데 결과를 모르는지(결제가 커밋했을 수도 있는지)
     * 결제가 판단을 마친 4xx만 "돈이 움직이지 않았다"로 본다. 5xx·응답 없음·연결 끊김·본문 해석 실패는 결과 모름이다.
     * 단, 연결 전 임시 구현(UnsupportedOperationException)은 요청을 보내지 않으므로 결과 모름이 아니다.
     */
    static boolean isResultUnknown(RuntimeException e) {
        if (e instanceof DomainException de) return de.statusCode() >= 500;
        return !(e instanceof UnsupportedOperationException);
    }

    /** 로그 규칙(exception_handling 9장): 업무 예외 4xx는 INFO·스택 없음, 5xx·그 밖의 예외는 ERROR·스택 포함 */
    private static void logFailure(Long settlementId, SettlementTrigger trigger, RuntimeException e) {
        if (e instanceof DomainException de && de.statusCode() < 500) {
            log.info("정산 지급 실패 settlementId={} trigger={} [{}]", settlementId, trigger, de.getResultCode());
        } else {
            log.error("정산 지급 실패 settlementId={} trigger={}", settlementId, trigger, e);
        }
    }

    /**
     * 관리자 수동 재처리
     * MANUAL_REQUIRED만 가능(404·409는 지급 전에 바로 거절. PROCESSING도 결과 확인 중이라 409)
     * 성공하면 PAID, 실패하면 MANUAL_REQUIRED로 두고 실패 사유를 갱신, 결과를 모르면 PROCESSING
     * 판매자가 제재 상태이거나 원장 불일치가 남았으면 지급하지 않고 HOLD로 바꾼 결과(status HOLD, holdReason)를 그대로 돌려준다
     */
    public SettlementPayoutResult retryManually(Long settlementId, String adminReason) {
        Settlement settlement = settlementSupport.getSettlement(settlementId);
        if (settlement.getStatus() != SettlementStatus.MANUAL_REQUIRED) {
            throw new DomainException(SettlementErrorCode.SETTLEMENT_STATE_INVALID);
        }
        SettlementPayoutResult result = payout(settlementId, SettlementTrigger.ADMIN, null, adminReason);
        // 건너뛴 결과 중 HOLD는 지급 직전 확인(제재·원장 불일치)으로 보류된 것이라 이미 커밋됐다. 409 대신 바뀐 상태를 알려 준다
        if (!result.processed() && result.status() != SettlementStatus.HOLD) {
            throw new DomainException(SettlementErrorCode.SETTLEMENT_STATE_INVALID);
        }
        return result;
    }

    /** ① 선점 트랜잭션: 지급 전 확인을 모두 마치고 PROCESSING으로 커밋한다 */
    private Prepared prepare(Long settlementId, SellerDto seller, SettlementTrigger trigger, boolean manualRetry,
                             Long batchExecutionId, String adminReason) {
        LocalDateTime now = LocalDateTime.now();
        Settlement settlement = settlementSupport.getSettlementWithLock(settlementId);
        if (!settlement.isPayable(manualRetry, now)) {
            // 이미 PAID·HOLD·PROCESSING이거나 다른 작업이 먼저 처리함. 기다렸다가 잠금을 얻은 뒤 바뀐 상태를 보고 건너뛴다.
            settlementProcessHistoryRepository.save(SettlementProcessHistory.payout(settlementId,
                    settlement.nextAttemptNo(), trigger, SettlementHistoryResult.SKIPPED, null,
                    "지급 가능한 상태가 아님: " + settlement.getStatus(), adminReason, batchExecutionId, now));
            return Prepared.skip(SettlementPayoutResult.skipped(settlement));
        }
        int attemptNo = settlement.nextAttemptNo();

        // 지급 전 확인: 판매자가 제재 상태면 지급하지 않고 보류한다.
        // 제재 이벤트(SellerSanctionedEvent)가 도착해 보류하기 전의 틈을 막는다. 잠금 전에 조회한 판매자 정보의 상태만 본다
        if (seller.status() == SellerStatus.SANCTIONED) {
            settlement.hold(SANCTIONED_HOLD_REASON);
            settlementProcessHistoryRepository.save(
                    SettlementProcessHistory.held(settlementId, SANCTIONED_HOLD_REASON, trigger, now));
            log.info("판매자 제재 상태라 지급하지 않고 보류 settlementId={} sellerId={} trigger={}",
                    settlementId, settlement.getSellerId(), trigger);
            return Prepared.skip(SettlementPayoutResult.skipped(settlement));
        }

        // 지급 전 확인: 이 판매자의 지난 지급 원장 재대조 불일치가 풀리지 않았으면 지급하지 않고 보류한다.
        // 판별 단계(LedgerMismatchRule)는 READY 후보가 있는 판매자만 보므로, 이미 만들어진 정산서(재시도·보류 해제 후 READY 등)는 여기서 막는다.
        // 불일치가 풀리면 이 사유로 보류된 정산서는 원장 재대조가 자동 해제한다(SettlementLedgerRecheckUseCase)
        if (settlementSupport.hasUnresolvedLedgerMismatch(settlement.getSellerId())) {
            settlement.hold(LedgerMismatchRule.REASON);
            settlementProcessHistoryRepository.save(
                    SettlementProcessHistory.held(settlementId, LedgerMismatchRule.REASON, trigger, now));
            log.info("원장 재대조 불일치가 남아 지급하지 않고 보류 settlementId={} sellerId={} trigger={}",
                    settlementId, settlement.getSellerId(), trigger);
            return Prepared.skip(SettlementPayoutResult.skipped(settlement));
        }

        // 지급 전 확인: 정산서 합계 = 묶인 정산 후보 합계
        SettlementCandidateTotalsRow totals = settlementCandidateRepository.sumIncludedTotals(settlementId);
        if (!settlement.matchesCandidateTotals(totals.saleTotal(), totals.feeTotal(), totals.payoutTotal(),
                totals.count())) {
            throw new DomainException(SettlementErrorCode.SETTLEMENT_AMOUNT_MISMATCH);
        }

        List<SettledOrderAmountDto> orders = settlementCandidateRepository.sumSalePriceByOrder(settlementId).stream()
                .map(row -> new SettledOrderAmountDto(row.orderId(), row.amount()))
                .toList();
        SettlementTransferCommand command = new SettlementTransferCommand(settlement.getIdempotencyKey(), settlementId,
                seller.memberId(), settlement.getPayoutAmount(), settlement.getTotalFee(), orders);

        settlement.markProcessing(now, manualRetry);
        return new Prepared(null, command, attemptNo);
    }

    /** ③ 결과 기록 트랜잭션: 결제가 지급을 돌려줬다 */
    private SettlementPayoutResult complete(Long settlementId, int attemptNo, TransferResultDto transferred,
                                            SettlementTrigger trigger, Long batchExecutionId, String adminReason) {
        LocalDateTime now = LocalDateTime.now();
        Settlement settlement = settlementSupport.getSettlementWithLock(settlementId);
        SettlementStatus current = settlement.getStatus();
        if (current == SettlementStatus.PAID) {
            // 응답을 받는 사이 재확인 스케줄러가 먼저 PAID로 확정했다(같은 멱등 키라 지급은 한 번뿐)
            settlementProcessHistoryRepository.save(SettlementProcessHistory.payout(settlementId, attemptNo, trigger,
                    SettlementHistoryResult.SKIPPED, null, "지급 응답을 받았지만 이미 PAID로 확정됨",
                    adminReason, batchExecutionId, now));
            return SettlementPayoutResult.skipped(settlement);
        }
        if (current == SettlementStatus.HOLD) {
            return completeWhileHeld(settlement, attemptNo, transferred, trigger, batchExecutionId, adminReason, now);
        }
        // READY·FAILED·MANUAL_REQUIRED: 응답이 늦어 재확인이 "지급 기록 없음"으로 먼저 실패 처리했다(그 뒤 보류 해제로 READY 포함).
        // 결제 응답이 이 정산서 멱등 키로 지급됐다는 근거이므로 아래에서 PROCESSING과 같이 확정한다
        // 같은 멱등 키의 지급 내역이 이미 있었는데 금액이 다르면 재시도 불가(돈은 이미 움직였으므로 사람이 확인)
        if (!settlement.matchesTransfer(transferred.payoutAmount(), transferred.feeAmount())) {
            DomainException mismatch = new DomainException(SettlementErrorCode.SETTLEMENT_AMOUNT_MISMATCH);
            settlement.markManualRequired(mismatch.getMsg());
            settlementProcessHistoryRepository.save(SettlementProcessHistory.payout(settlementId, attemptNo, trigger,
                    SettlementHistoryResult.MANUAL_REQUIRED, mismatch.getResultCode(), mismatch.getMsg(), adminReason,
                    batchExecutionId, now));
            log.error("정산 지급 금액이 정산서와 다름 settlementId={} payout={} fee={} transferredPayout={} transferredFee={}",
                    settlementId, settlement.getPayoutAmount(), settlement.getTotalFee(), transferred.payoutAmount(),
                    transferred.feeAmount());
            return SettlementPayoutResult.of(settlement, attemptNo, true);
        }

        settlement.markPaid(transferred.walletTransactionId(), now);
        settlementProcessHistoryRepository.save(SettlementProcessHistory.payout(settlementId, attemptNo, trigger,
                SettlementHistoryResult.SUCCEEDED, null, null, adminReason, batchExecutionId, now));
        log.info("정산 지급 완료 settlementId={} trigger={} attempt={} payout={} fee={}",
                settlementId, trigger, attemptNo, settlement.getPayoutAmount(), settlement.getTotalFee());
        return SettlementPayoutResult.of(settlement, attemptNo, true);
    }

    /**
     * ③ 지급은 됐는데 그사이 정산서가 보류(HOLD)됐다(재확인이 먼저 FAILED로 바꾼 뒤 관리자·제재 보류)
     * 돈이 이미 판매자에게 갔으므로 금액이 같으면 PAID로 맞추고 보류 사유는 이력에 남긴다(HOLD로 두면 미정산 금액에 계속 잡힌다).
     * 금액이 다르면 HOLD에서는 MANUAL_REQUIRED로 바꿀 수 없으므로 상태를 그대로 두고 에러 로그·이력으로 확인을 요청한다
     */
    private SettlementPayoutResult completeWhileHeld(Settlement settlement, int attemptNo, TransferResultDto transferred,
                                                     SettlementTrigger trigger, Long batchExecutionId,
                                                     String adminReason, LocalDateTime now) {
        Long settlementId = settlement.getId();
        String holdReason = settlement.getHoldReason();
        if (!settlement.matchesTransfer(transferred.payoutAmount(), transferred.feeAmount())) {
            settlementProcessHistoryRepository.save(SettlementProcessHistory.payout(settlementId, attemptNo, trigger,
                    SettlementHistoryResult.SKIPPED, SettlementErrorCode.SETTLEMENT_AMOUNT_MISMATCH.resultCode(),
                    "지급됐지만 금액이 정산서와 다르고 보류 중이라 상태를 바꾸지 않음(확인 필요) walletTxId=%s"
                            .formatted(transferred.walletTransactionId()),
                    adminReason, batchExecutionId, now));
            log.error("정산 지급은 됐지만 금액이 다르고 보류 중이라 상태를 바꾸지 못함 settlementId={} walletTxId={}",
                    settlementId, transferred.walletTransactionId());
            return SettlementPayoutResult.skipped(settlement);
        }
        settlement.markPaidWhileHeld(transferred.walletTransactionId(), now);
        settlementProcessHistoryRepository.save(SettlementProcessHistory.payout(settlementId, attemptNo, trigger,
                SettlementHistoryResult.SUCCEEDED, null,
                "보류 중이었지만 지급 응답으로 지급을 확인해 PAID(보류 사유: %s)".formatted(holdReason),
                adminReason, batchExecutionId, now));
        log.warn("보류 중이던 정산서가 이미 지급돼 PAID로 맞춤 settlementId={} holdReason={}", settlementId, holdReason);
        return SettlementPayoutResult.of(settlement, attemptNo, true);
    }

    /** ③ 결과 모름: PROCESSING으로 두고 이력만 남긴다. 재확인 스케줄러가 확정한다 */
    private SettlementPayoutResult recordUnknown(Long settlementId, int attemptNo, SettlementTrigger trigger,
                                                 Long batchExecutionId, String adminReason, RuntimeException cause) {
        LocalDateTime now = LocalDateTime.now();
        Settlement settlement = settlementSupport.getSettlementWithLock(settlementId);
        if (settlement.getStatus() != SettlementStatus.PROCESSING) {
            return SettlementPayoutResult.skipped(settlement);   // 그사이 재확인이 먼저 확정했다
        }
        settlementProcessHistoryRepository.save(SettlementProcessHistory.payout(settlementId, attemptNo, trigger,
                SettlementHistoryResult.PROCESSING, errorCodeOf(cause),
                "지급 요청 결과 모름, 재확인 대기: " + reasonOf(cause), adminReason, batchExecutionId, now));
        return SettlementPayoutResult.of(settlement, attemptNo, true);
    }

    /**
     * ② 실패 기록 트랜잭션
     * fromProcessing=false: ①에서 실패(돈이 움직이기 전). 지급 가능한 상태일 때만 기록한다
     * fromProcessing=true: ②에서 결제가 확실히 거절(4xx). 아직 PROCESSING일 때만 기록한다
     * 그 사이 다른 작업이 상태를 바꿨으면 상태·실패 횟수를 덮어쓰지 않고 SKIPPED 이력만 남긴다
     */
    private SettlementPayoutResult recordFailure(Long settlementId, SettlementTrigger trigger, boolean manualRetry,
                                                 Long batchExecutionId, String adminReason, RuntimeException cause,
                                                 boolean fromProcessing) {
        LocalDateTime now = LocalDateTime.now();
        Settlement settlement = settlementSupport.getSettlementWithLock(settlementId);
        String errorCode = errorCodeOf(cause);
        String reason = reasonOf(cause);
        boolean recordable = fromProcessing
                ? settlement.getStatus() == SettlementStatus.PROCESSING
                : settlement.isPayable(manualRetry, now);
        if (!recordable) {
            settlementProcessHistoryRepository.save(SettlementProcessHistory.payout(settlementId,
                    settlement.nextAttemptNo(), trigger, SettlementHistoryResult.SKIPPED, errorCode,
                    "지급 실패 후 다른 작업이 먼저 상태를 바꿔 실패로 기록하지 않음(현재 %s): %s"
                            .formatted(settlement.getStatus(), reason),
                    adminReason, batchExecutionId, now));
            return SettlementPayoutResult.skipped(settlement);
        }
        int attemptNo = settlement.nextAttemptNo();

        if (manualRetry || SettlementPolicy.isNonRetryable(errorCode)) {
            settlement.markManualRequired(reason);
        } else {
            settlement.markFailed(reason, now, settlementPolicy.getMaxAttempts(),
                    settlementPolicy.getRetryIntervalMinutes());
        }
        SettlementHistoryResult result = settlement.getStatus() == SettlementStatus.MANUAL_REQUIRED
                ? SettlementHistoryResult.MANUAL_REQUIRED : SettlementHistoryResult.FAILED;
        settlementProcessHistoryRepository.save(SettlementProcessHistory.payout(settlementId, attemptNo, trigger,
                result, errorCode, reason, adminReason, batchExecutionId, now));
        return SettlementPayoutResult.of(settlement, attemptNo, true);
    }

    /**
     * 지급 결과 재확인 (PROCESSING이 된 지 processingRecheckMinutes가 지난 정산서)
     * 결제 원장을 같은 멱등 키로 조회(트랜잭션 밖)한 뒤, 잠가서 아직 PROCESSING일 때만 확정한다.
     *   지급 기록 있음 + 금액 같음 → PAID / 금액 다름 → MANUAL_REQUIRED / 기록 없음 → FAILED(재시도 대상, 3회면 MANUAL_REQUIRED)
     *   단 관리자 재처리로 시작한 PROCESSING이면 기록 없음 → MANUAL_REQUIRED(자동 재시도로 되돌리지 않는다)
     * 기록 없음으로 FAILED가 된 뒤 늦은 요청이 결제에 커밋돼도, 그 응답이 오면 ③(complete)이 FAILED·MANUAL_REQUIRED에서도 PAID로 확정하고,
     * 응답을 못 받았어도 다음 재시도가 같은 SETTLEMENT-{id}로 transfer를 부르면 결제가 기존 결과를 돌려주므로 PAID로 수렴한다.
     * 원장 조회가 실패하면 PROCESSING으로 두고 다음 회차에 다시 본다.
     */
    public SettlementPayoutResult reconcileProcessing(Long settlementId) {
        Settlement current = settlementSupport.getSettlement(settlementId);
        if (current.getStatus() != SettlementStatus.PROCESSING) return SettlementPayoutResult.skipped(current);
        Optional<TransferResultDto> ledger = settlementPaymentPort.findTransfer(current.getIdempotencyKey());
        return newTransaction.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            Settlement settlement = settlementSupport.getSettlementWithLock(settlementId);
            if (settlement.getStatus() != SettlementStatus.PROCESSING) {
                return SettlementPayoutResult.skipped(settlement);   // 응답이 늦게 와 ③이 먼저 확정했다
            }
            int attemptNo = settlement.nextAttemptNo();
            SettlementHistoryResult result;
            String reason;
            if (ledger.isEmpty()) {
                reason = "결과 확인: 결제 원장에 지급 기록 없음";
                if (settlement.isProcessingManual()) {
                    // 관리자 재처리였다. MANUAL_REQUIRED는 자동 재시도로 되돌리지 않는다
                    settlement.markManualRequired(reason);
                } else {
                    settlement.markFailed(reason, now, settlementPolicy.getMaxAttempts(),
                            settlementPolicy.getRetryIntervalMinutes());
                }
                result = settlement.getStatus() == SettlementStatus.MANUAL_REQUIRED
                        ? SettlementHistoryResult.MANUAL_REQUIRED : SettlementHistoryResult.FAILED;
            } else if (settlement.matchesTransfer(ledger.get().payoutAmount(), ledger.get().feeAmount())) {
                reason = null;
                settlement.markPaid(ledger.get().walletTransactionId(), now);
                result = SettlementHistoryResult.SUCCEEDED;
            } else {
                reason = "결과 확인: 결제 원장의 지급 금액이 정산서와 다름";
                settlement.markManualRequired(reason);
                result = SettlementHistoryResult.MANUAL_REQUIRED;
                log.error("정산 지급 결과 확인: 원장 금액이 정산서와 다름 settlementId={}", settlementId);
            }
            settlementProcessHistoryRepository.save(SettlementProcessHistory.payout(settlementId, attemptNo,
                    SettlementTrigger.RETRY_SCHEDULER, result, null, reason, null, null, now));
            log.info("정산 지급 결과 확인 settlementId={} result={} status={}", settlementId, result,
                    settlement.getStatus());
            return SettlementPayoutResult.of(settlement, attemptNo, true);
        });
    }

    /** 이력에 남길 에러 코드
     * 업무 예외는 resultCode, 그 밖은 예외 클래스 이름 */
    private static String errorCodeOf(RuntimeException e) {
        if (e instanceof DomainException de) return de.getResultCode();
        return e.getClass().getSimpleName();
    }

    /** 실패 사유
     * 스택트레이스·개인정보는 넣지 않는다(상세는 로그) */
    private static String reasonOf(RuntimeException e) {
        if (e instanceof DomainException de) return de.getMsg();
        return "지급 처리 중 오류가 발생했습니다(" + e.getClass().getSimpleName() + ").";
    }
}
