package com.backend.boundedContext.payment.out;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backend.boundedContext.payment.domain.Topup;

import jakarta.persistence.LockModeType;

public interface TopupRepository extends JpaRepository<Topup, Long> {

    Optional<Topup> findByIdAndMemberId(Long id, Long memberId);

    // 회원의 충전 목록 (정렬은 Pageable로 받음, idx_topup_member 사용)
    Page<Topup> findByMemberId(Long memberId, Pageable pageable);

    Optional<Topup> findByTossOrderIdAndMemberId(String tossOrderId, Long memberId);

    // 승인 시작 전 충전 건 행을 잠금. 같은 충전 건의 동시 승인 요청은 순서대로 처리
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Topup t where t.tossOrderId = :tossOrderId and t.memberId = :memberId")
    Optional<Topup> findByTossOrderIdAndMemberIdForUpdate(@Param("tossOrderId") String tossOrderId, @Param("memberId") Long memberId);

    boolean existsByPaymentKey(String paymentKey);

    // 실패 기록 전 충전 건 행을 잠금. 같은 충전 건의 승인 요청(PROCESSING 선점)과 동시에 처리되지 않게 함
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Topup t where t.id = :id and t.memberId = :memberId")
    Optional<Topup> findByIdAndMemberIdForUpdate(@Param("id") Long id, @Param("memberId") Long memberId);

    // 승인 결과 반영 전 충전 건 행을 잠금. 승인 응답과 스케줄러가 같은 충전 건을 동시에 반영하지 않게 함
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Topup t where t.id = :id")
    Optional<Topup> findByIdForUpdate(@Param("id") Long id);
}
