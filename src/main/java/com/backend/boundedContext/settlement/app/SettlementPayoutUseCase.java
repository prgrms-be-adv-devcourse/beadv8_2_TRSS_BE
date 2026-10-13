package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementErrorCode;
import com.backend.boundedContext.settlement.domain.SettlementHistoryResult;
import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import com.backend.boundedContext.settlement.domain.SettlementProcessHistory;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.in.dto.SettlementPayoutResult;
import com.backend.boundedContext.settlement.out.SettlementCandidateRepository;
import com.backend.boundedContext.settlement.out.SettlementCandidateTotalsRow;
import com.backend.boundedContext.settlement.out.SettlementProcessHistoryRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.payment.dto.SettledOrderAmountDto;
import com.backend.shared.payment.dto.SettlementTransferCommand;
import com.backend.shared.payment.dto.TransferResultDto;
import com.backend.shared.payment.out.PaymentApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 정산서 1건 지급 (판매자 1명 = 정산서 1건 = 처리 단위 1개)
 *
 * ① 지급 (하나의 트랜잭션)
 *    정산서 잠금 → 지급 가능한 상태인지 확인 → 정산서 합계 = 후보 합계 확인
 *    → PaymentApi.transfer(홀딩 차감·판매자/관리자 지갑 입금·원장 기록, 같은 트랜잭션에 참여)
 *    → 정산서 PAID → 처리 이력(성공)
 *
 * ② 실패 기록 (별도 트랜잭션)
 *    ①이 예외로 롤백된 뒤 정산서를 FAILED(또는 MANUAL_REQUIRED)로 바꾸고 실패 사유·처리 이력(실패)을 저장
 *
 * ①의 변경은 함께 커밋되거나 함께 롤백되므로 "돈은 나갔는데 PAID가 아닌 정산서"가 생기지 않는다
 * 두 트랜잭션 모두 TransactionTemplate(REQUIRES_NEW)으로 열어, 호출한 쪽(배치·스케줄러·관리자)에 트랜잭션이 있어도 따로 커밋된다
 */
@Slf4j
@Component
public class SettlementPayoutUseCase {

    private final SettlementSupport settlementSupport;
    private final SettlementCandidateRepository settlementCandidateRepository;
    private final SettlementProcessHistoryRepository settlementProcessHistoryRepository;
    private final PaymentApi paymentApi;
    private final MemberApi memberApi;
    private final TransactionTemplate newTransaction;

    public SettlementPayoutUseCase(SettlementSupport settlementSupport,
                                   SettlementCandidateRepository settlementCandidateRepository,
                                   SettlementProcessHistoryRepository settlementProcessHistoryRepository,
                                   PaymentApi paymentApi,
                                   MemberApi memberApi,
                                   PlatformTransactionManager transactionManager) {
        this.settlementSupport = settlementSupport;
        this.settlementCandidateRepository = settlementCandidateRepository;
        this.settlementProcessHistoryRepository = settlementProcessHistoryRepository;
        this.paymentApi = paymentApi;
        this.memberApi = memberApi;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 월 정산 Job·재시도 스케줄러·관리자 재처리가 모두 이 메서드를 쓴다(같은 지급 로직)
     * 지급 가능한 상태가 아니면 건너뛰고(processed=false), 건너뛴 경우는 실패 횟수에 세지 않는다
     */
    public SettlementPayoutResult payout(Long settlementId, SettlementTrigger trigger, Long batchExecutionId,
                                         String adminReason) {
        try {
            return newTransaction.execute(status -> pay(settlementId, trigger, batchExecutionId, adminReason));
        } catch (RuntimeException e) {
            log.warn("정산 지급 실패 settlementId={} trigger={}", settlementId, trigger, e);
            return newTransaction.execute(status ->
                    recordFailure(settlementId, trigger, batchExecutionId, adminReason, e));
        }
    }

    /**
     * 관리자 수동 재처리
     * MANUAL_REQUIRED만 가능(404·409는 지급 전에 바로 거절)
     * 성공하면 PAID, 실패하면 MANUAL_REQUIRED로 두고 실패 사유를 갱신
     */
    public SettlementPayoutResult retryManually(Long settlementId, String adminReason) {
        Settlement settlement = settlementSupport.getSettlement(settlementId);
        if (settlement.getStatus() != SettlementStatus.MANUAL_REQUIRED) {
            throw new DomainException(SettlementErrorCode.SETTLEMENT_STATE_INVALID);
        }
        SettlementPayoutResult result = payout(settlementId, SettlementTrigger.ADMIN, null, adminReason);
        if (!result.processed()) throw new DomainException(SettlementErrorCode.SETTLEMENT_STATE_INVALID);
        return result;
    }

    /** ① 지급 트랜잭션 */
    private SettlementPayoutResult pay(Long settlementId, SettlementTrigger trigger, Long batchExecutionId,
                                       String adminReason) {
        LocalDateTime now = LocalDateTime.now();
        Settlement settlement = settlementSupport.getSettlementWithLock(settlementId);
        if (!settlement.isPayableBy(trigger, now)) {
            // 이미 PAID·HOLD이거나 다른 작업이 먼저 처리함. 기다렸다가 잠금을 얻은 뒤 바뀐 상태를 보고 건너뛴다.
            settlementProcessHistoryRepository.save(SettlementProcessHistory.payout(settlementId,
                    settlement.nextAttemptNo(), trigger, SettlementHistoryResult.SKIPPED, null,
                    "지급 가능한 상태가 아님: " + settlement.getStatus(), adminReason, batchExecutionId, now));
            return SettlementPayoutResult.skipped(settlement);
        }
        int attemptNo = settlement.nextAttemptNo();

        // 지급 전 확인: 정산서 합계 = 묶인 정산 후보 합계
        SettlementCandidateTotalsRow totals = settlementCandidateRepository.sumIncludedTotals(settlementId);
        if (!settlement.matchesCandidateTotals(totals.saleTotal(), totals.feeTotal(), totals.payoutTotal(),
                totals.count())) {
            throw new DomainException(SettlementErrorCode.SETTLEMENT_AMOUNT_MISMATCH);
        }

        List<SettledOrderAmountDto> orders = settlementCandidateRepository.sumSalePriceByOrder(settlementId).stream()
                .map(row -> new SettledOrderAmountDto(row.orderId(), row.amount()))
                .toList();
        Long sellerMemberId = memberApi.getSeller(settlement.getSellerId()).memberId();

        // 홀딩 차감·지갑 입금·원장 기록. 이 트랜잭션에 참여하므로 아래가 실패하면 함께 롤백된다
        TransferResultDto transferred = paymentApi.transfer(new SettlementTransferCommand(
                settlement.getIdempotencyKey(), settlementId, sellerMemberId,
                settlement.getPayoutAmount(), settlement.getTotalFee(), orders));
        // 같은 멱등 키의 지급 내역이 이미 있었는데 금액이 다르면 재시도 불가
        if (!settlement.matchesTransfer(transferred.payoutAmount(), transferred.feeAmount())) {
            throw new DomainException(SettlementErrorCode.SETTLEMENT_AMOUNT_MISMATCH);
        }

        settlement.markPaid(transferred.walletTransactionId(), now);
        settlementProcessHistoryRepository.save(SettlementProcessHistory.payout(settlementId, attemptNo, trigger,
                SettlementHistoryResult.SUCCEEDED, null, null, adminReason, batchExecutionId, now));
        log.info("정산 지급 완료 settlementId={} trigger={} attempt={} payout={} fee={}",
                settlementId, trigger, attemptNo, settlement.getPayoutAmount(), settlement.getTotalFee());
        return SettlementPayoutResult.of(settlement, attemptNo, true);
    }

    /** ② 실패 기록 트랜잭션
     *      ①은 이미 롤백됐으므로 정산서를 다시 잠가서 읽는다 */
    private SettlementPayoutResult recordFailure(Long settlementId, SettlementTrigger trigger, Long batchExecutionId,
                                                 String adminReason, RuntimeException cause) {
        LocalDateTime now = LocalDateTime.now();
        Settlement settlement = settlementSupport.getSettlementWithLock(settlementId);
        if (!settlement.isPayableBy(trigger, now)) {
            // 그 사이 다른 작업이 상태를 바꿨으면(이미 실패 기록·보류·지급 완료 등) 실패로 덮어쓰지 않는다
            return SettlementPayoutResult.skipped(settlement);
        }
        int attemptNo = settlement.nextAttemptNo();
        String errorCode = errorCodeOf(cause);
        String reason = reasonOf(cause);

        if (trigger == SettlementTrigger.ADMIN || SettlementPolicy.isNonRetryable(errorCode)) {
            settlement.markManualRequired(reason);
        } else {
            settlement.markFailed(reason, now, SettlementPolicy.MAX_ATTEMPTS, SettlementPolicy.RETRY_INTERVAL_MINUTES);
        }
        SettlementHistoryResult result = settlement.getStatus() == SettlementStatus.MANUAL_REQUIRED
                ? SettlementHistoryResult.MANUAL_REQUIRED : SettlementHistoryResult.FAILED;
        settlementProcessHistoryRepository.save(SettlementProcessHistory.payout(settlementId, attemptNo, trigger,
                result, errorCode, reason, adminReason, batchExecutionId, now));
        return SettlementPayoutResult.of(settlement, attemptNo, true);
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