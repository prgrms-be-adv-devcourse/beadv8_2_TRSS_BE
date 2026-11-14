package com.backend.boundedContext.settlement.out;

import com.backend.boundedContext.settlement.domain.SettlementHistoryStage;
import com.backend.boundedContext.settlement.domain.SettlementHistoryTargetType;
import com.backend.boundedContext.settlement.domain.SettlementProcessHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/** 정산 처리 이력
 *
 * 저장만 하고 고치지 않는다 */
public interface SettlementProcessHistoryRepository extends JpaRepository<SettlementProcessHistory, Long> {

    /** 대상 1건의 이력을 처리 순서대로 (관리자 이력 조회용) */
    List<SettlementProcessHistory> findByTargetTypeAndTargetIdOrderByIdAsc(SettlementHistoryTargetType targetType,
                                                                           Long targetId);

    /**
     * 원장 재대조 불일치가 아직 풀리지 않은 정산서 ID
     * 정산서별 가장 마지막 VERIFY 이력이 MANUAL_REQUIRED인 것. 다시 재대조해 OK(SUCCEEDED)가 쌓이면 빠진다
     */
    @Query("""
            select h.targetId from SettlementProcessHistory h
             where h.targetType = com.backend.boundedContext.settlement.domain.SettlementHistoryTargetType.SETTLEMENT
               and h.stage = com.backend.boundedContext.settlement.domain.SettlementHistoryStage.VERIFY
               and h.result = com.backend.boundedContext.settlement.domain.SettlementHistoryResult.MANUAL_REQUIRED
               and h.id = (select max(h2.id) from SettlementProcessHistory h2
                            where h2.targetType = h.targetType
                              and h2.stage = h.stage
                              and h2.targetId = h.targetId)
            """)
    List<Long> findUnresolvedLedgerMismatchSettlementIds();

    /** 대상 1건의 단계별 가장 마지막 이력 (원장 재대조: 직전 결과와 같으면 이력을 다시 남기지 않는다) */
    Optional<SettlementProcessHistory> findFirstByTargetTypeAndTargetIdAndStageOrderByIdDesc(
            SettlementHistoryTargetType targetType, Long targetId, SettlementHistoryStage stage);
}