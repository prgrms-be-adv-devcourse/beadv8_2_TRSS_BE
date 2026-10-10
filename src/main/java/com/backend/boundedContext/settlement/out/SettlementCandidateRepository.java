package com.backend.boundedContext.settlement.out;

import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 정산 후보 저장·조회.
 * TODO 집계·미리보기·미정산 금액 쿼리는 추후 구현 예정
 */
public interface SettlementCandidateRepository extends JpaRepository<SettlementCandidate, Long> {

    /** 같은 확정 이벤트를 다시 받았는지 먼저 확인한다(멱등). DB 유니크 제약은 동시 요청용 안전망 */
    boolean existsByOrderItemId(Long orderItemId);
}
