package com.backend.boundedContext.settlement.out;

import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 정산 후보 저장·조회
 * TODO 집계·미리보기·미정산 금액 쿼리는 추후 구현 예정
 */
public interface SettlementCandidateRepository extends JpaRepository<SettlementCandidate, Long> {

    /** 같은 확정 이벤트를 다시 받았는지 먼저 확인한다(멱등). DB 유니크 제약은 동시 요청용 안전망 */
    boolean existsByOrderItemId(Long orderItemId);

    /** 집계 대상 판매자: READY 후보 중 정산월이 대상월 이하인 판매자. afterSellerId 커서로 끝까지 한 번씩만 본다 */
    @Query("""
            select distinct c.sellerId from SettlementCandidate c
             where c.status = com.backend.boundedContext.settlement.domain.SettlementCandidateStatus.READY
               and c.settlementMonth <= :month
               and c.sellerId > :afterSellerId
             order by c.sellerId
            """)
    List<Long> findAggregatableSellerIds(@Param("month") String month, @Param("afterSellerId") Long afterSellerId,
                                         Pageable pageable);

    /** 한 판매자의 집계 대상 후보를 잠가서 가져온다(늦게 생긴 이전 달 후보 포함) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select c from SettlementCandidate c
             where c.sellerId = :sellerId
               and c.status = com.backend.boundedContext.settlement.domain.SettlementCandidateStatus.READY
               and c.settlementMonth <= :month
             order by c.id
            """)
    List<SettlementCandidate> findWithLockReadyBySellerIdUntilMonth(@Param("sellerId") Long sellerId,
                                                                    @Param("month") String month);

    @Query("select c from SettlementCandidate c where c.settlement.id = :settlementId order by c.id")
    List<SettlementCandidate> findBySettlementIdOrderByIdAsc(@Param("settlementId") Long settlementId);

    /** 지급 명령에 넣을 주문별 판매 금액 합(= 그 주문 홀딩에서 이 판매자 몫, 지급액 + 수수료) */
    @Query("""
            select new com.backend.boundedContext.settlement.out.SettlementOrderAmountRow(c.orderId, sum(c.salePrice))
              from SettlementCandidate c
             where c.settlement.id = :settlementId
               and c.status = com.backend.boundedContext.settlement.domain.SettlementCandidateStatus.INCLUDED
             group by c.orderId
             order by c.orderId
            """)
    List<SettlementOrderAmountRow> sumSalePriceByOrder(@Param("settlementId") Long settlementId);

    /** 정산서에 묶인 후보 합계 (지급 전 합계 확인용) */
    @Query("""
            select new com.backend.boundedContext.settlement.out.SettlementCandidateTotalsRow(
                       coalesce(sum(c.salePrice), 0L), coalesce(sum(c.fee), 0L),
                       coalesce(sum(c.payoutAmount), 0L), count(c))
              from SettlementCandidate c
             where c.settlement.id = :settlementId
               and c.status = com.backend.boundedContext.settlement.domain.SettlementCandidateStatus.INCLUDED
            """)
    SettlementCandidateTotalsRow sumIncludedTotals(@Param("settlementId") Long settlementId);
}
