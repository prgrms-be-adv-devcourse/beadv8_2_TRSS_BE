package com.backend.boundedContext.payment.out;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backend.boundedContext.payment.domain.WalletTransaction;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    boolean existsByIdempotencyKey(String idempotencyKey);
}
