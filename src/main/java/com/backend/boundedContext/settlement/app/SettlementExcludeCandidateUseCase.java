package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import com.backend.boundedContext.settlement.domain.SettlementProcessHistory;
import com.backend.boundedContext.settlement.in.dto.SettlementCandidateResponse;
import com.backend.boundedContext.settlement.out.SettlementProcessHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 관리자 정산 후보 제외: 아직 정산서에 묶이지 않은 READY 후보를 EXCLUDED로 바꾸고 사유·처리 이력을 남긴다
 *
 * 후보를 잠근 뒤 바꾸므로 월 정산 집계와 겹치면 집계가 끝날 때까지 기다리고,
 * 그 사이 INCLUDED가 됐으면 엔티티가 409 SETTLEMENT_CANDIDATE_STATE_INVALID를 던진다.
 * 제외된 금액은 집계·미정산 금액에서 빠진다. 결제 컨텍스트의 홀딩은 그대로 둔다(정산 컨텍스트 결정: 당분간 HELD 유지).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementExcludeCandidateUseCase {

    private final SettlementSupport settlementSupport;
    private final SettlementProcessHistoryRepository settlementProcessHistoryRepository;

    public SettlementCandidateResponse exclude(Long candidateId, String reason) {
        String strippedReason = reason.strip();
        SettlementCandidate candidate = settlementSupport.getCandidateWithLock(candidateId);
        candidate.exclude(strippedReason);   // READY가 아니면 409
        settlementProcessHistoryRepository.save(
                SettlementProcessHistory.candidateExcluded(candidateId, strippedReason, LocalDateTime.now()));
        log.info("정산 후보 제외 candidateId={} sellerId={} orderItemId={}", candidateId, candidate.getSellerId(),
                candidate.getOrderItemId());
        return SettlementCandidateResponse.from(candidate);
    }
}