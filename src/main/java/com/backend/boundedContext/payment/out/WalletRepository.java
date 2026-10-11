package com.backend.boundedContext.payment.out;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backend.boundedContext.payment.domain.Wallet;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    boolean existsByMemberId(Long memberId);

    Optional<Wallet> findByMemberId(Long memberId);
}
