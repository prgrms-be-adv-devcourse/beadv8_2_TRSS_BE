package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import com.backend.boundedContext.settlement.domain.SettlementJudgement;
import com.backend.boundedContext.settlement.domain.SettlementProcessHistory;
import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.domain.judge.SettlementJudgeChain;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 월 정산 Job 1단계(판별): 지급 직전에 "시간이 지나면 바뀔 수 있는 조건"을 판매자 1명 단위로 다시 본다
 *
 * 규칙은 SettlementJudgeChain(책임 연쇄)에 있고, 여기서는 규칙이 볼 상황(후보·판매자 상태·원장 불일치)을 모아 넘긴 뒤
 * 결과를 처리 이력에 남긴다. 상태는 바꾸지 않는다(확정 단계가 판정대로 정산서를 만든다).
 *
 * 관리자 제외(EXCLUDED)는 READY 후보 조회에서 이미 빠진다.
 * 정산 모듈이 가진 데이터(후보·정산서·처리 이력)와 이미 있는 계약(SettlementMemberPort)만으로 판별한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementJudgeUseCase {

    private final SettlementCandidateRepository settlementCandidateRepository;
    private final SettlementRepository settlementRepository;
    private final SettlementProcessHistoryRepository settlementProcessHistoryRepository;
    private final SettlementMemberPort settlementMemberPort;
    private final SettlementJudgeChain settlementJudgeChain;

    /** 원장 재대조 불일치가 풀리지 않은 판매자 ID (판별 Step이 한 번 읽어 두고 판매자마다 넘긴다) */
    public Set<Long> findLedgerMismatchSellerIds() {
        List<Long> settlementIds = settlementProcessHistoryRepository.findUnresolvedLedgerMismatchSettlementIds();
        if (settlementIds.isEmpty()) return Set.of();
        return new HashSet<>(settlementRepository.findSellerIdsByIdIn(settlementIds));
    }

    /** 판매자 1명 판별. READY 후보가 없으면 판정할 것이 없으므로 PASS를 돌려주고 이력을 남기지 않는다 */
    public SettlementJudgement judgeSeller(Long sellerId, YearMonth targetMonth, Set<Long> ledgerMismatchSellerIds,
                                          SettlementTrigger trigger, Long batchExecutionId) {
        List<SettlementCandidate> candidates =
                settlementCandidateRepository.findReadyBySellerIdUntilMonth(sellerId, targetMonth.toString());
        if (candidates.isEmpty()) return SettlementJudgement.pass();

        SettlementJudgeContext context = new SettlementJudgeContext(sellerId, candidates, sellerStatusOf(sellerId),
                ledgerMismatchSellerIds.contains(sellerId));
        SettlementJudgement judgement = settlementJudgeChain.judge(context);

        LocalDateTime now = LocalDateTime.now();
        for (Long candidateId : judgement.rejectedCandidateIds()) {
            settlementProcessHistoryRepository.save(SettlementProcessHistory.candidateBlocked(candidateId,
                    judgement.errorCode(), judgement.reason(), trigger, batchExecutionId, now));
        }
        settlementProcessHistoryRepository.save(
                SettlementProcessHistory.sellerJudged(sellerId, judgement, trigger, batchExecutionId, now));
        if (!judgement.isPass()) {
            log.info("정산 판별 sellerId={} month={} result={} reason={}", sellerId, targetMonth, judgement.type(),
                    judgement.reason());
        }
        return judgement;
    }

    /** 판매자 행이 없으면 null (지급 단계의 SELLER_NOT_FOUND에 맡긴다) */
    private SellerStatus sellerStatusOf(Long sellerId) {
        return settlementMemberPort.getSellers(List.of(sellerId)).stream()
                .map(SellerDto::status)
                .findFirst()
                .orElse(null);
    }
}
