package com.backend.boundedContext.payment.out;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backend.boundedContext.payment.domain.Topup;

import jakarta.persistence.LockModeType;

public interface TopupRepository extends JpaRepository<Topup, Long> {

    Optional<Topup> findByIdAndMemberId(Long id, Long memberId);

    Optional<Topup> findByTossOrderIdAndMemberId(String tossOrderId, Long memberId);

    // 승인 시작 전 충전 건 행을 잠금. 같은 충전 건의 동시 승인 요청은 순서대로 처리
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Topup t where t.tossOrderId = :tossOrderId and t.memberId = :memberId")
    Optional<Topup> findByTossOrderIdAndMemberIdForUpdate(@Param("tossOrderId") String tossOrderId, @Param("memberId") Long memberId);

    boolean existsByPaymentKey(String paymentKey);
}
