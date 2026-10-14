package com.backend.boundedContext.payment.out;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backend.boundedContext.payment.domain.TopupUsage;

public interface TopupUsageRepository extends JpaRepository<TopupUsage, Long> {

    List<TopupUsage> findAllByPayment_Id(Long paymentId);
}
