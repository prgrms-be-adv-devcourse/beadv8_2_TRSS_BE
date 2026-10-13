package com.backend.boundedContext.payment.out;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backend.boundedContext.payment.domain.Payment;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
}
