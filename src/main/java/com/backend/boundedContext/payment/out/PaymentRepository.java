package com.backend.boundedContext.payment.out;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.backend.boundedContext.payment.domain.Payment;
import com.backend.boundedContext.payment.domain.PaymentStatus;

// 관리자 결제 조회는 AdminPaymentSpecifications 조건으로 findAll(spec, pageable)을 씀
public interface PaymentRepository extends JpaRepository<Payment, Long>, JpaSpecificationExecutor<Payment> {

    // 같은 멱등 키로 이미 처리한 결제 (멱등 키는 UNIQUE라 최대 1건)
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    // 주문에 이 상태의 결제가 있는지 (PAID는 주문당 1건)
    boolean existsByOrderIdAndStatus(Long orderId, PaymentStatus status);
}
