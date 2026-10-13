package com.backend.boundedContext.payment.out;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backend.boundedContext.payment.domain.WalletHolding;

import jakarta.persistence.LockModeType;

public interface WalletHoldingRepository extends JpaRepository<WalletHolding, Long> {

    boolean existsByOrderId(Long orderId);

    // 정산 지급 전에 주문들의 홀딩을 잠금. 데드락을 막기 위해 주문 ID 오름차순으로 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from WalletHolding h where h.orderId in :orderIds order by h.orderId")
    List<WalletHolding> findAllByOrderIdInForUpdate(@Param("orderIds") Collection<Long> orderIds);
}
