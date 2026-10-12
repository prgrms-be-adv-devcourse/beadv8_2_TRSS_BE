package com.backend.boundedContext.payment.out;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backend.boundedContext.payment.domain.Topup;

public interface TopupRepository extends JpaRepository<Topup, Long> {

    Optional<Topup> findByIdAndMemberId(Long id, Long memberId);

    Optional<Topup> findByTossOrderIdAndMemberId(String tossOrderId, Long memberId);
}
