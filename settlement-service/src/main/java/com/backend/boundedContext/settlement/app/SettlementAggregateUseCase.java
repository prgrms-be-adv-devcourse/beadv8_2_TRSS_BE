package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.domain.SettlementHistoryResult;
import com.backend.boundedContext.settlement.domain.SettlementJudgement;
import com.backend.boundedContext.settlement.domain.judge.LedgerMismatchRule;
import com.backend.boundedContext.settlement.domain.SettlementHoldType;
import com.backend.boundedContext.settlement.domain.SettlementProcessHistory;
import com.backend.boundedContext.settlement.domain.judge.SellerStatusRule;
import com.backend.boundedContext.settlement.domain.judge.SettlementJudgeContext;
import com.backend.boundedContext.settlement.out.SettlementCandidateRepository;
import com.backend.boundedContext.settlement.out.SettlementProcessHistoryRepository;
import com.backend.boundedContext.settlement.out.SettlementRepository;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.boundedContext.settlement.domain.judge.SettlementJudgeChain;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * 월 정산 Job의 확정 Step: 판매자 1명의 READY 후보(정산월 ≤ 대상월)를 잠그고, 그 후보로 판별 규칙을 실행한 뒤 정산서를 만든다
 * 판매자 1명 = 트랜잭션 1개라서 한 판매자가 실패해도 다른 판매자 확정은 그대로 커밋된다
 *
 * 판별은 확정 안에서, 실제로 묶을(잠근) 후보로 한다. 그래서 판별과 확정 사이에 들어온 후보, 재시작으로 며칠 뒤 확정하는 경우도
 * 따로 처리할 것이 없다(예전처럼 판정을 Step 사이로 넘기지 않는다).
 * 판매자 상태(제재 여부)는 Tasklet이 묶음마다 트랜잭션 밖에서 한 번에 조회해 넘긴다(후보 행을 잠근 채 회원 HTTP를 부르지 않는다)
 *   판별 결과 BLOCK → 확정하지 않음(후보 READY 유지, 다음 실행에서 다시 판별), HOLD → HOLD 정산서, PASS → READY 정산서
 * 지급은 Job에서 하지 않는다. 정산서에 지급 예정일(payableOn)을 정해 두면 지급 스케줄러가 그날부터 지급한다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementAggregateUseCase {

    /** 관리자 제재 상태는 WITHDRAWN(본인 철회 완료)이 아니라 SANCTIONED(관리자 제재) */
    static final String SANCTIONED_HOLD_REASON = SellerStatusRule.SANCTIONED_REASON;

    private final SettlementRepository settlementRepository;
    private final SettlementCandidateRepository settlementCandidateRepository;
    private final SettlementProcessHistoryRepository settlementProcessHistoryRepository;
    private final SettlementJudgeChain settlementJudgeChain;

    /**
     * 판매자 1명 확정
     *
     * @param sellerStatus   판매자 상태(묶음마다 트랜잭션 밖에서 조회). 판매자 행이 없으면 null(지급 단계의 SELLER_NOT_FOUND에 맡긴다)
     * @param ledgerMismatch 원장 재대조 불일치가 풀리지 않은 판매자인지
     * @param payableOn      새로 만드는 정산서의 지급 예정일
     */
    public SettlementConfirmResult confirmSeller(Long sellerId, YearMonth targetMonth, Long batchExecutionId,
                                                 SettlementTrigger trigger, SellerStatus sellerStatus,
                                                 boolean ledgerMismatch, LocalDate payableOn) {
        LocalDateTime now = LocalDateTime.now();
        String month = targetMonth.toString();
        List<SettlementCandidate> candidates =
                settlementCandidateRepository.findWithLockReadyBySellerIdUntilMonth(sellerId, month);
        if (candidates.isEmpty()) return SettlementConfirmResult.none();

        SettlementJudgement judgement = settlementJudgeChain.judge(
                new SettlementJudgeContext(sellerId, candidates, sellerStatus, ledgerMismatch));
        recordJudgement(sellerId, judgement, trigger, batchExecutionId, now);
        if (judgement.type() == SettlementJudgement.Type.BLOCK) {
            // 사람 확인이 필요한 판매자(후보 금액, 수수료율 불일치). 후보는 READY로 남고 다음 실행에서 다시 판별한다
            settlementProcessHistoryRepository.save(SettlementProcessHistory.aggregateFailed(trigger,
                    judgement.errorCode(), "sellerId=%d month=%s 판별 BLOCK으로 확정하지 않음: %s".formatted(
                            sellerId, month, judgement.reason()), batchExecutionId, now));
            log.warn("정산 확정 막음 sellerId={} month={} reason={}", sellerId, month, judgement.reason());
            return SettlementConfirmResult.of(0, judgement.type());
        }
        String holdReason = judgement.type() == SettlementJudgement.Type.HOLD ? judgement.reason() : null;
        SettlementHoldType holdType = holdTypeOf(holdReason);

        Optional<Settlement> existing = settlementRepository.findWithLockBySellerIdAndSettlementMonth(sellerId, month);
        Settlement settlement;
        if (existing.isPresent()) {
            settlement = existing.get();
            if (!settlement.canAcceptCandidates()) {
                // 이미 지급된 정산서에는 더 담지 않는다. 후보는 READY로 남아 다음 달 정산서로 이월된다.
                settlementProcessHistoryRepository.save(SettlementProcessHistory.aggregated(settlement.getId(),
                        trigger, SettlementHistoryResult.SKIPPED, "정산서가 %s 상태라 후보 %d건을 다음 달로 이월".formatted(
                                settlement.getStatus(), candidates.size()), batchExecutionId, now));
                return SettlementConfirmResult.of(0, judgement.type());
            }
        } else {
            settlement = settlementRepository.save(Settlement.create(sellerId, month, holdType, holdReason));
            settlement.assignIdempotencyKey();   // IDENTITY라 save()에서 INSERT가 끝나 id가 있다. 같은 Tx에서 키 확정
        }
        settlement.schedulePayout(payableOn);

        candidates.forEach(settlement::addCandidate);
        if (holdReason != null && settlement.getStatus() == SettlementStatus.READY) {
            // 이미 있던 READY 정산서에 후보를 더 담는데 이번 판별이 HOLD면 함께 보류한다
            settlement.hold(holdType, holdReason);
            settlementProcessHistoryRepository.save(
                    SettlementProcessHistory.held(settlement.getId(), holdReason, trigger, now));
        }
        settlementProcessHistoryRepository.save(SettlementProcessHistory.aggregated(settlement.getId(),
                trigger, SettlementHistoryResult.SUCCEEDED, null, batchExecutionId, now));
        log.info("정산 확정 sellerId={} month={} settlementId={} status={} items={} payout={} payableOn={}",
                sellerId, month, settlement.getId(), settlement.getStatus(), candidates.size(),
                settlement.getPayoutAmount(), payableOn);
        return SettlementConfirmResult.of(candidates.size(), judgement.type());
    }

    /** 판별 결과를 이력에 남긴다(막힌 후보마다 + 판매자 판정) */
    private void recordJudgement(Long sellerId, SettlementJudgement judgement, SettlementTrigger trigger,
                                 Long batchExecutionId, LocalDateTime now) {
        for (Long candidateId : judgement.rejectedCandidateIds()) {
            settlementProcessHistoryRepository.save(SettlementProcessHistory.candidateBlocked(candidateId,
                    judgement.errorCode(), judgement.reason(), trigger, batchExecutionId, now));
        }
        settlementProcessHistoryRepository.save(
                SettlementProcessHistory.sellerJudged(sellerId, judgement, trigger, batchExecutionId, now));
    }

    /** 판매자 확정이 예외로 실패했을 때 별도 트랜잭션에서 실패 이력만 남긴다(후보는 READY로 남아 다음 실행에서 다시 확정) */
    public void recordFailure(Long sellerId, YearMonth targetMonth, Long batchExecutionId, SettlementTrigger trigger,
                              String errorCode, String reason) {
        settlementProcessHistoryRepository.save(SettlementProcessHistory.aggregateFailed(trigger, errorCode,
                "sellerId=%d month=%s %s".formatted(sellerId, targetMonth, reason), batchExecutionId,
                LocalDateTime.now()));
    }

    /** 묶음 전체를 확정하지 못하고 Step을 멈췄을 때 실패 이력 1건(판매자 ID 범위). 후보는 READY로 남아 같은 달 재실행에서 다시 확정 */
    public void recordChunkFailure(Long fromSellerId, Long toSellerId, YearMonth targetMonth, Long batchExecutionId,
                                   SettlementTrigger trigger, String errorCode, String reason) {
        settlementProcessHistoryRepository.save(SettlementProcessHistory.aggregateFailed(trigger, errorCode,
                "sellerId=%d..%d month=%s %s".formatted(fromSellerId, toSellerId, targetMonth, reason), batchExecutionId,
                LocalDateTime.now()));
    }

    /** 확정 단계 보류 사유 → 보류 종류. 확정 단계 보류는 판별 규칙 두 가지(원장 불일치, 판매자 제재)에서만 나온다 */
    private static SettlementHoldType holdTypeOf(String holdReason) {
        if (holdReason == null) return null;
        return LedgerMismatchRule.REASON.equals(holdReason)
                ? SettlementHoldType.LEDGER_MISMATCH : SettlementHoldType.SANCTION;
    }
}
