package com.backend.boundedContext.payment.out;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupStatus;

import jakarta.persistence.LockModeType;

public interface TopupRepository extends JpaRepository<Topup, Long>, JpaSpecificationExecutor<Topup> {

    Optional<Topup> findByIdAndMemberId(Long id, Long memberId);

    // 회원의 충전 목록 (정렬은 Pageable로 받음, idx_topup_member 사용)
    Page<Topup> findByMemberId(Long memberId, Pageable pageable);

    Optional<Topup> findByTossOrderIdAndMemberId(String tossOrderId, Long memberId);

    // 승인 시작 전 충전 건 행을 잠금. 같은 충전 건의 동시 승인 요청은 순서대로 처리
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Topup t where t.tossOrderId = :tossOrderId and t.memberId = :memberId")
    Optional<Topup> findByTossOrderIdAndMemberIdForUpdate(@Param("tossOrderId") String tossOrderId, @Param("memberId") Long memberId);

    boolean existsByPaymentKey(String paymentKey);

    // 회원에게 이 상태들 중 하나인 충전 건이 있는지 (idx_topup_member 사용)
    boolean existsByMemberIdAndStatusIn(Long memberId, Collection<TopupStatus> statuses);

    // 실패 기록 전 충전 건 행을 잠금. 같은 충전 건의 승인 요청(PROCESSING 선점)과 동시에 처리되지 않게 함
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Topup t where t.id = :id and t.memberId = :memberId")
    Optional<Topup> findByIdAndMemberIdForUpdate(@Param("id") Long id, @Param("memberId") Long memberId);

    // 결제 시 차감할 충전 건: 승인 완료(DONE)이고 남은 금액이 있는 것을 오래된 순(승인 시각, ID)으로 잠금
    // 결제는 지갑을 먼저 잠근 뒤 호출한다. 승인 반영(complete)은 PROCESSING 충전 건을 잠그므로 서로 다른 행이라 교착이 생기지 않음
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select t from Topup t
            where t.memberId = :memberId and t.status = :status and t.remainingAmount > 0
            order by t.approvedAt, t.id
            """)
    List<Topup> findUsableForUpdate(@Param("memberId") Long memberId, @Param("status") TopupStatus status);

    // 승인 결과 반영 전 충전 건 행을 잠금. 승인 응답과 스케줄러가 같은 충전 건을 동시에 반영하지 않게 함
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Topup t where t.id = :id")
    Optional<Topup> findByIdForUpdate(@Param("id") Long id);

    // 결과 확인 대상: 기준 시각 이전부터 같은 상태로 남은 충전 건을 오래된 순으로 (건수 제한은 Pageable)
    @Query("select t from Topup t where t.status = :status and t.updatedAt < :cutoff order by t.updatedAt, t.id")
    List<Topup> findStale(@Param("status") TopupStatus status, @Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    /**
     * READY 만료: 기준 시각 이전에 만든 READY 충전 건을 한 번에 만료 상태로 바꾸고 바뀐 건수를 반환
     * 조건부 UPDATE라 같은 순간 승인 요청이 PROCESSING으로 선점한 건은 바뀌지 않음
     * 벌크 UPDATE는 JPA 감사(@LastModifiedDate)를 거치지 않으므로 updatedAt을 직접 넣음
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Topup t
               set t.status = :expiredStatus, t.failReason = :reason, t.updatedAt = :now
             where t.status = :readyStatus and t.createdAt < :cutoff""")
    int expireReadyTopups(@Param("readyStatus") TopupStatus readyStatus,
                          @Param("expiredStatus") TopupStatus expiredStatus,
                          @Param("reason") String reason,
                          @Param("cutoff") LocalDateTime cutoff,
                          @Param("now") LocalDateTime now);
}
