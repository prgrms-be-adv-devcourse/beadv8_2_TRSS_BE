package com.backend.boundedContext.settlement.out;

import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 정산 후보 저장·조회
 * 관리자 후보 목록은 조건이 모두 선택이라 Specification(JpaSpecificationExecutor)으로 조회한다
 */
public interface SettlementCandidateRepository extends JpaRepository<SettlementCandidate, Long>,
        JpaSpecificationExecutor<SettlementCandidate> {

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

    /** 정산서에 묶인 후보 합계 (지급 전 합계 확인용)
     *
     * 인텔리제이 쿼리 타입 계산 오류(coalesce(sum(c.salePrice), 0L)의 결과를 int로 계산. 0L의 L(Long 표시)을 인식하지 못함)로 인해 오류 표시됨
     * -> 0 처리를 쿼리 대신 record하는 방식으로 수정
     * */
    @Query("""
            select new com.backend.boundedContext.settlement.out.SettlementCandidateTotalsRow(
                       sum(c.salePrice), sum(c.fee), sum(c.payoutAmount), count(c))
              from SettlementCandidate c
             where c.settlement.id = :settlementId
               and c.status = com.backend.boundedContext.settlement.domain.SettlementCandidateStatus.INCLUDED
            """)
    SettlementCandidateTotalsRow sumIncludedTotals(@Param("settlementId") Long settlementId);

    /** 관리자 후보 제외 전 잠금 조회. 집계(findWithLockReadyBySellerIdUntilMonth)와 동시에 같은 후보를 바꾸지 않게 한다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from SettlementCandidate c where c.id = :id")
    Optional<SettlementCandidate> findWithLockById(@Param("id") Long id);

    /**
     * 미정산 금액(지급액 합) = READY 후보 + PAID가 아닌 정산서에 묶인 INCLUDED 후보 (EXCLUDED는 세지 않는다)
     * 보류(HOLD)·실패 중인 정산서의 금액도 포함한다. 후보가 없으면 null이므로 호출한 쪽이 0으로 바꾼다
     */
    @Query("""
            select sum(c.payoutAmount) from SettlementCandidate c
              left join c.settlement s
             where c.sellerId = :sellerId
               and (c.status = com.backend.boundedContext.settlement.domain.SettlementCandidateStatus.READY
                    or (c.status = com.backend.boundedContext.settlement.domain.SettlementCandidateStatus.INCLUDED
                        and s.status <> com.backend.boundedContext.settlement.domain.SettlementStatus.PAID))
            """)
    Long sumUnsettledPayout(@Param("sellerId") Long sellerId);

    /** 판매자 정산 예정 금액: 아직 정산서에 묶이지 않은 READY 후보를 정산월별로 더한다 (오래된 달부터) */
    @Query("""
            select new com.backend.boundedContext.settlement.out.SettlementMonthAmountRow(
                       c.settlementMonth, count(c), sum(c.salePrice), sum(c.fee), sum(c.payoutAmount))
              from SettlementCandidate c
             where c.sellerId = :sellerId
               and c.status = com.backend.boundedContext.settlement.domain.SettlementCandidateStatus.READY
             group by c.settlementMonth
             order by c.settlementMonth
            """)
    List<SettlementMonthAmountRow> sumReadyBySellerIdGroupByMonth(@Param("sellerId") Long sellerId);

    /**
     * 관리자 정산 미리보기: 대상월 월 정산 Job이 집계할 후보(READY, 정산월 ≤ 대상월)를 판매자별로 더한다
     * 집계 Step(findAggregatableSellerIds)과 같은 조건이다. 판매자 ID 순으로 페이지를 나눈다
     */
    @Query(value = """
            select new com.backend.boundedContext.settlement.out.SettlementSellerAmountRow(
                       c.sellerId, count(c),
                       sum(case when c.settlementMonth < :month then 1L else 0L end),
                       sum(c.salePrice), sum(c.fee), sum(c.payoutAmount))
              from SettlementCandidate c
             where c.status = com.backend.boundedContext.settlement.domain.SettlementCandidateStatus.READY
               and c.settlementMonth <= :month
             group by c.sellerId
             order by c.sellerId
            """,
            countQuery = """
            select count(distinct c.sellerId) from SettlementCandidate c
             where c.status = com.backend.boundedContext.settlement.domain.SettlementCandidateStatus.READY
               and c.settlementMonth <= :month
            """)
    Page<SettlementSellerAmountRow> sumAggregatableGroupBySeller(@Param("month") String month, Pageable pageable);

    /** 관리자 정산 미리보기 맨 위 합계 (판매자 수·후보 수·금액). 미리보기 페이지와 같은 조건 */
    @Query("""
            select new com.backend.boundedContext.settlement.out.SettlementAmountTotalsRow(
                       count(distinct c.sellerId), count(c), sum(c.salePrice), sum(c.fee), sum(c.payoutAmount))
              from SettlementCandidate c
             where c.status = com.backend.boundedContext.settlement.domain.SettlementCandidateStatus.READY
               and c.settlementMonth <= :month
            """)
    SettlementAmountTotalsRow sumAggregatableTotals(@Param("month") String month);
}