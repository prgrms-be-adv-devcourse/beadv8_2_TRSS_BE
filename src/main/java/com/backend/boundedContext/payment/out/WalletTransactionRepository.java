package com.backend.boundedContext.payment.out;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.backend.boundedContext.payment.domain.WalletTransaction;

// 내 거래 내역 목록은 WalletTransactionSpecifications 조건으로 findAll(spec, pageable)을 씀
public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long>, JpaSpecificationExecutor<WalletTransaction> {

    Optional<WalletTransaction> findByIdempotencyKey(String idempotencyKey);
}
