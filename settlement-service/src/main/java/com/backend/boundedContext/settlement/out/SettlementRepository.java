package com.backend.boundedContext.settlement.out;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 정산서 저장·조회. 상태를 바꾸기 전에는 반드시 잠금 조회(findWithLock...)를 쓴다.
 * 지급 트랜잭션이 행을 잡고 있으면 나중에 들어온 작업은 기다렸다가 바뀐 상태를 보고 건너뛴다(동시 실행 방지)
 */
public interface SettlementRepository extends JpaRepository<Settlement, Long>, JpaSpecificationExecutor<Settlement> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Settlement s where s.id = :id")
    Optional<Settlement> findWithLockById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Settlement s where s.sellerId = :sellerId and s.settlementMonth = :month")
    Optional<Settlement> findWithLockBySellerIdAndSettlementMonth(@Param("sellerId") Long sellerId,
                                                                  @Param("month") String month);

    /** 월 정산 Job 지급 Step 대상: 그 달의 READY 정산서. afterId 커서로 끝까지 한 번씩만 본다 */
    @Query("""
            select s.id from Settlement s
             where s.settlementMonth = :month
               and s.status = com.backend.boundedContext.settlement.domain.SettlementStatus.READY
               and s.id > :afterId
             order by s.id
            """)
    List<Long> findMonthlyPayoutTargetIds(@Param("month") String month, @Param("afterId") Long afterId,
                                          Pageable pageable);

    /** 재시도 스케줄러 대상: 다음 재시도 일시가 지난 FAILED + READY(보류 해제·집계 후 지급 전에 멈춘 건) */
    @Query("""
            select s.id from Settlement s
             where s.status = com.backend.boundedContext.settlement.domain.SettlementStatus.READY
                or (s.status = com.backend.boundedContext.settlement.domain.SettlementStatus.FAILED
                    and s.nextRetryAt <= :now)
             order by s.id
            """)
    List<Long> findRetryTargetIds(@Param("now") LocalDateTime now, Pageable pageable);

    /**
     * 원장 재대조 대상: 지급일이 since 이후인 PAID 정산서. afterId 커서로 한 번씩만 본다
     */
    @Query("""
            select s.id from Settlement s
             where s.status = com.backend.boundedContext.settlement.domain.SettlementStatus.PAID
               and s.paidAt >= :since
               and s.id > :afterId
             order by s.id
            """)
    List<Long> findLedgerRecheckTargetIds(@Param("since") LocalDateTime since, @Param("afterId") Long afterId,
                                          Pageable pageable);

    /**
     * 이 판매자의 정산서 중 원장 재대조 불일치가 아직 풀리지 않은 것이 있는지
     * (정산서별 가장 마지막 VERIFY 이력이 MANUAL_REQUIRED. SettlementProcessHistoryRepository.findUnresolvedLedgerMismatchSettlementIds와 같은 기준)
     * 지급 직전 확인·보류 해제·불일치 해소 후 자동 해제가 쓴다
     */
    @Query("""
            select count(s) > 0 from Settlement s
             where s.sellerId = :sellerId
               and s.id in (select h.targetId from SettlementProcessHistory h
                             where h.targetType = com.backend.boundedContext.settlement.domain.SettlementHistoryTargetType.SETTLEMENT
                               and h.stage = com.backend.boundedContext.settlement.domain.SettlementHistoryStage.VERIFY
                               and h.result = com.backend.boundedContext.settlement.domain.SettlementHistoryResult.MANUAL_REQUIRED
                               and h.id = (select max(h2.id) from SettlementProcessHistory h2
                                            where h2.targetType = h.targetType
                                              and h2.stage = h.stage
                                              and h2.targetId = h.targetId))
            """)
    boolean existsUnresolvedLedgerMismatchBySellerId(@Param("sellerId") Long sellerId);

    /** 이 사유로 보류(HOLD)된 정산서가 있는 판매자 ID (원장 불일치 보류 정리: 불일치가 없는데 남은 보류를 찾는다) */
    @Query("""
            select distinct s.sellerId from Settlement s
             where s.status = com.backend.boundedContext.settlement.domain.SettlementStatus.HOLD
               and s.holdReason = :holdReason
             order by s.sellerId
            """)
    List<Long> findSellerIdsByHoldReason(@Param("holdReason") String holdReason);

    /** 정산서 ID들의 판매자 ID (원장 불일치가 남은 판매자 찾기) */
    @Query("select distinct s.sellerId from Settlement s where s.id in :ids")
    List<Long> findSellerIdsByIdIn(@Param("ids") Collection<Long> ids);

    /** 판매자 본인 정산서 조회. 소유자를 조회 조건에 넣어 남의 정산서는 404가 되게 한다(예외 처리 5.1) */
    Optional<Settlement> findByIdAndSellerId(Long id, Long sellerId);

    /**
     * 판매자 제재(holdSeller) 대상: 이 판매자의 지급 전 정산서(READY·FAILED·MANUAL_REQUIRED)를 잠가서 가져온다
     * 지급 트랜잭션이 잡고 있는 정산서는 지급이 끝날 때까지 기다린다. PAID가 되면 조건에서 빠지므로 보류하지 않는다
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from Settlement s
             where s.sellerId = :sellerId
               and s.status in :statuses
             order by s.id
            """)
    List<Settlement> findWithLockBySellerIdAndStatusIn(@Param("sellerId") Long sellerId,
                                                       @Param("statuses") Collection<SettlementStatus> statuses);

    /** 관리자 정산 미리보기: 대상월에 이미 만들어진 정산서(판매자별 상태 표시용) */
    List<Settlement> findBySettlementMonthAndSellerIdIn(String settlementMonth, Collection<Long> sellerIds);
}