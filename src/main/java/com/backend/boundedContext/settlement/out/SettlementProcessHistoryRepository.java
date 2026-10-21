package com.backend.boundedContext.settlement.out;

import com.backend.boundedContext.settlement.domain.SettlementHistoryTargetType;
import com.backend.boundedContext.settlement.domain.SettlementProcessHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 정산 처리 이력
 *
 * 저장만 하고 고치지 않는다 */
public interface SettlementProcessHistoryRepository extends JpaRepository<SettlementProcessHistory, Long> {

    /** 대상 1건의 이력을 처리 순서대로 (관리자 이력 조회용) */
    List<SettlementProcessHistory> findByTargetTypeAndTargetIdOrderByIdAsc(SettlementHistoryTargetType targetType,
                                                                           Long targetId);
}