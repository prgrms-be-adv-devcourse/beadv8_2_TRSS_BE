package com.backend.boundedContext.payment.out;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backend.boundedContext.payment.domain.WalletHolding;
import com.backend.boundedContext.payment.domain.WalletHoldingStatus;

import jakarta.persistence.LockModeType;

public interface WalletHoldingRepository extends JpaRepository<WalletHolding, Long> {

    boolean existsByOrderId(Long orderId);

    // 결제 건들의 홀딩 (관리자 결제 조회의 보관 금액 상태). 결제 성공 건만 홀딩이 있음
    List<WalletHolding> findAllByPaymentIdIn(Collection<Long> paymentIds);

    // 지갑의 홀딩 중 남은 금액(홀딩 금액 - 정산 누적 금액) 합계. 홀딩이 없으면 0
    @Query("""
            select coalesce(sum(h.amount - h.settledAmount), 0) from WalletHolding h
            where h.wallet.id = :walletId 
                and h.status = :status
            """)
    long sumRemainingAmount(@Param("walletId") Long walletId, @Param("status") WalletHoldingStatus status);

    // 정산 지급 전에 주문들의 홀딩을 잠금. 데드락을 막기 위해 주문 ID 오름차순으로 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from WalletHolding h where h.orderId in :orderIds order by h.orderId")
    List<WalletHolding> findAllByOrderIdInForUpdate(@Param("orderIds") Collection<Long> orderIds);
}
