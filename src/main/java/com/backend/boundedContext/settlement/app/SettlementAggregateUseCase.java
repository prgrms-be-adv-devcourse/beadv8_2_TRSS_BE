package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import com.backend.boundedContext.settlement.domain.SettlementHistoryResult;
import com.backend.boundedContext.settlement.domain.SettlementProcessHistory;
import com.backend.boundedContext.settlement.out.SettlementCandidateRepository;
import com.backend.boundedContext.settlement.out.SettlementProcessHistoryRepository;
import com.backend.boundedContext.settlement.out.SettlementRepository;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * 월별 정산서 집계 : 판매자 1명의 READY 후보(정산월 ≤ 대상월)를 그 판매자의 대상월 정산서에 묶는다
 * 판매자 1명 = 트랜잭션 1개라서 한 판매자가 실패해도 다른 판매자 집계는 그대로 커밋된다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementAggregateUseCase {

    /** 관리자 제재 상태는 WITHDRAWN(본인 철회 완료)이 아니라 SANCTIONED(관리자 제재) */
    static final String SANCTIONED_HOLD_REASON = "관리자 제재된 판매자라 정산을 보류합니다.";

    private final SettlementRepository settlementRepository;
    private final SettlementCandidateRepository settlementCandidateRepository;
    private final SettlementProcessHistoryRepository settlementProcessHistoryRepository;
    private final MemberApi memberApi;

    /** @return 정산서에 묶은 후보 수 (이월하거나 대상이 없으면 0) */
    public int aggregateSeller(Long sellerId, YearMonth targetMonth, Long batchExecutionId, SettlementTrigger trigger) {
        LocalDateTime now = LocalDateTime.now();
        String month = targetMonth.toString();
        List<SettlementCandidate> candidates =
                settlementCandidateRepository.findWithLockReadyBySellerIdUntilMonth(sellerId, month);
        if (candidates.isEmpty()) return 0;

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
            settlement = settlementRepository.save(Settlement.create(sellerId, month, holdReasonOf(sellerId)));
            settlement.assignIdempotencyKey();   // IDENTITY라 save()에서 INSERT가 끝나 id가 있다. 같은 Tx에서 키 확정
        }

        candidates.forEach(settlement::addCandidate);
        settlementProcessHistoryRepository.save(SettlementProcessHistory.aggregated(settlement.getId(),
                trigger, SettlementHistoryResult.SUCCEEDED, null, batchExecutionId, now));
        log.info("정산 집계 sellerId={} month={} settlementId={} status={} items={} payout={}",
                sellerId, month, settlement.getId(), settlement.getStatus(), candidates.size(),
                settlement.getPayoutAmount());
        return candidates.size();
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
    private String holdReasonOf(Long sellerId) {
        boolean sanctioned = memberApi.getSellers(List.of(sellerId)).stream()
                .map(SellerDto::status)
                .anyMatch(status -> status == SellerStatus.SANCTIONED);
        return sanctioned ? SANCTIONED_HOLD_REASON : null;
    }
}