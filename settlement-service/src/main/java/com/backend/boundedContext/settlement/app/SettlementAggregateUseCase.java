package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.domain.SettlementHistoryResult;
import com.backend.boundedContext.settlement.domain.SettlementJudgement;
import com.backend.boundedContext.settlement.domain.SettlementProcessHistory;
import com.backend.boundedContext.settlement.domain.judge.CandidateAmountIntegrityRule;
import com.backend.boundedContext.settlement.domain.judge.SellerStatusRule;
import com.backend.boundedContext.settlement.domain.judge.SettlementJudgeContext;
import com.backend.boundedContext.settlement.out.SettlementCandidateRepository;
import com.backend.boundedContext.settlement.out.SettlementMemberPort;
import com.backend.boundedContext.settlement.out.SettlementProcessHistoryRepository;
import com.backend.boundedContext.settlement.out.SettlementRepository;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * 월 정산 Job 2단계(확정): 판매자 1명의 READY 후보(정산월 ≤ 대상월)를 그 판매자의 대상월 정산서에 묶는다
 * 판매자 1명 = 트랜잭션 1개라서 한 판매자가 실패해도 다른 판매자 확정은 그대로 커밋된다
 *
 * 판별 단계의 판정을 따른다: BLOCK → 확정하지 않음(후보 READY 유지), HOLD → 정산서를 HOLD로, PASS → 보통 정산서
 * 판정이 없으면(판별 중 오류, 판별 이후 새로 후보가 생긴 판매자, 판별 Step이 없던 실행의 재시작)
 * 후보 금액 확인(CandidateAmountIntegrityRule)을 여기서 직접 하고, 어긋나면 BLOCK과 같이 확정하지 않는다.
 * 금액이 맞으면 예전처럼 제재 여부만 보고 HOLD를 정한다(원장 불일치는 지급 직전에 SettlementPayoutUseCase가 본다).
 * 지급 단계의 합계 확인(matchesCandidateTotals)은 정산서 합계를 같은 후보 금액으로 만들므로 금액 변조를 잡지 못한다.
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
    private final SettlementMemberPort settlementMemberPort;
    private final CandidateAmountIntegrityRule candidateAmountIntegrityRule;

    /** 판정 없이 확정 (판별 Step이 없던 실행·테스트용). 제재 여부만 보고 HOLD를 정한다 */
    public int aggregateSeller(Long sellerId, YearMonth targetMonth, Long batchExecutionId, SettlementTrigger trigger) {
        return aggregateSeller(sellerId, targetMonth, batchExecutionId, trigger, null);
    }

    /**
     * @param judgement 판별 단계 판정. null이면 판정이 없는 것
     * @return 정산서에 묶은 후보 수 (이월·BLOCK이거나 대상이 없으면 0)
     */
    public int aggregateSeller(Long sellerId, YearMonth targetMonth, Long batchExecutionId, SettlementTrigger trigger,
                               SettlementJudgement judgement) {
        LocalDateTime now = LocalDateTime.now();
        String month = targetMonth.toString();
        if (judgement != null && judgement.type() == SettlementJudgement.Type.BLOCK) {
            // 판별에서 사람 확인이 필요하다고 본 판매자. 후보는 READY로 남고 다음 실행에서 다시 판별한다
            settlementProcessHistoryRepository.save(SettlementProcessHistory.aggregateFailed(trigger,
                    judgement.errorCode(), "sellerId=%d month=%s 판별 BLOCK으로 확정하지 않음: %s".formatted(
                            sellerId, month, judgement.reason()), batchExecutionId, now));
            return 0;
        }
        List<SettlementCandidate> candidates =
                settlementCandidateRepository.findWithLockReadyBySellerIdUntilMonth(sellerId, month);
        if (candidates.isEmpty()) return 0;
        if (judgement == null && blockedByCandidateAmounts(sellerId, month, candidates, batchExecutionId, trigger, now)) {
            return 0;
        }
        String holdReason = holdReasonOf(sellerId, judgement);

        Optional<Settlement> existing = settlementRepository.findWithLockBySellerIdAndSettlementMonth(sellerId, month);
        Settlement settlement;
        if (existing.isPresent()) {
            settlement = existing.get();
            if (!settlement.canAcceptCandidates()) {
                // 이미 지급된 정산서에는 더 담지 않는다. 후보는 READY로 남아 다음 달 정산서로 이월된다.
                settlementProcessHistoryRepository.save(SettlementProcessHistory.aggregated(settlement.getId(),
                        trigger, SettlementHistoryResult.SKIPPED, "정산서가 %s 상태라 후보 %d건을 다음 달로 이월".formatted(
                                settlement.getStatus(), candidates.size()), batchExecutionId, now));
                return 0;
            }
        } else {
            settlement = settlementRepository.save(Settlement.create(sellerId, month, holdReason));
            settlement.assignIdempotencyKey();   // IDENTITY라 save()에서 INSERT가 끝나 id가 있다. 같은 Tx에서 키 확정
        }

        candidates.forEach(settlement::addCandidate);
        if (holdReason != null && settlement.getStatus() == SettlementStatus.READY) {
            // 이미 있던 READY 정산서에 후보를 더 담는데 이번 판정이 HOLD면 함께 보류한다
            settlement.hold(holdReason);
            settlementProcessHistoryRepository.save(
                    SettlementProcessHistory.held(settlement.getId(), holdReason, trigger, now));
        }
        settlementProcessHistoryRepository.save(SettlementProcessHistory.aggregated(settlement.getId(),
                trigger, SettlementHistoryResult.SUCCEEDED, null, batchExecutionId, now));
        log.info("정산 집계 sellerId={} month={} settlementId={} status={} items={} payout={}",
                sellerId, month, settlement.getId(), settlement.getStatus(), candidates.size(),
                settlement.getPayoutAmount());
        return candidates.size();
    }

    /**
     * 판정이 없을 때 후보 금액 스냅샷을 직접 확인한다. 어긋나면 판별 BLOCK과 같은 이력을 남기고 true(확정하지 않음)
     * 후보는 READY로 남아 다음 실행의 판별 단계에서 다시 본다
     */
    private boolean blockedByCandidateAmounts(Long sellerId, String month, List<SettlementCandidate> candidates,
                                              Long batchExecutionId, SettlementTrigger trigger, LocalDateTime now) {
        SettlementJudgement amountCheck = candidateAmountIntegrityRule.judge(
                new SettlementJudgeContext(sellerId, candidates, null, false));
        if (amountCheck.isPass()) return false;
        for (Long candidateId : amountCheck.rejectedCandidateIds()) {
            settlementProcessHistoryRepository.save(SettlementProcessHistory.candidateBlocked(candidateId,
                    amountCheck.errorCode(), amountCheck.reason(), trigger, batchExecutionId, now));
        }
        settlementProcessHistoryRepository.save(SettlementProcessHistory.aggregateFailed(trigger,
                amountCheck.errorCode(), "sellerId=%d month=%s 판정 없이 확정하려다 후보 금액 확인에서 막음: %s".formatted(
                        sellerId, month, amountCheck.reason()), batchExecutionId, now));
        log.warn("정산 집계 후보 금액 불일치로 확정하지 않음 sellerId={} month={} reason={}", sellerId, month,
                amountCheck.reason());
        return true;
    }

    /** 판매자 집계가 예외로 실패했을 때 별도 트랜잭션에서 실패 이력만 남긴다(후보는 READY로 남아 다음 실행에서 다시 집계) */
    public void recordFailure(Long sellerId, YearMonth targetMonth, Long batchExecutionId, SettlementTrigger trigger,
                              String errorCode, String reason) {
        settlementProcessHistoryRepository.save(SettlementProcessHistory.aggregateFailed(trigger, errorCode,
                "sellerId=%d month=%s %s".formatted(sellerId, targetMonth, reason), batchExecutionId,
                LocalDateTime.now()));
    }

    /**
     * 관리자 제재(SANCTIONED)된 판매자면 정산서를 처음부터 HOLD로 만든다.
     * (SettlementApi.holdSeller의 "이후 만들어지는 정산서도 HOLD" 약속을 여기서 지킨다)
     * 판매자 행이 없으면(아직 회원 시드에 판매자 행이 없음 등) 제재 여부만 모르는 것이므로 보통 정산서로 만들고,
     * 지급 단계에서 판매자 조회 실패(404-SELLER_NOT_FOUND)로 재시도 불가 처리된다.
     */
    private String holdReasonOf(Long sellerId, SettlementJudgement judgement) {
        if (judgement != null) return judgement.type() == SettlementJudgement.Type.HOLD ? judgement.reason() : null;
        return sanctionedHoldReasonOf(sellerId);
    }

    private String sanctionedHoldReasonOf(Long sellerId) {
        boolean sanctioned = settlementMemberPort.getSellers(List.of(sellerId)).stream()
                .map(SellerDto::status)
                .anyMatch(status -> status == SellerStatus.SANCTIONED);
        return sanctioned ? SANCTIONED_HOLD_REASON : null;
    }
}