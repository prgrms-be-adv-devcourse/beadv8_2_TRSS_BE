package com.backend.boundedContext.payment.out;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backend.boundedContext.payment.domain.WalletTransaction;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    boolean existsByIdempotencyKey(String idempotencyKey);

    Optional<WalletTransaction> findByIdempotencyKey(String idempotencyKey);
}
