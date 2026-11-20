package com.backend.boundedContext.payment.out;

import java.time.LocalDate;

import org.springframework.data.jpa.domain.Specification;

import com.backend.boundedContext.payment.domain.Payment;

// 관리자 결제 조회 검색 조건. 조건이 없으면(null) 걸지 않음
public final class AdminPaymentSpecifications {

    private AdminPaymentSpecifications() {
    }

    public static Specification<Payment> memberEquals(Long memberId) {
        if (memberId == null) return Specification.unrestricted();
        return (root, query, cb) -> cb.equal(root.get("memberId"), memberId);
    }

    // 주문번호 정확히 일치 (idx_payment_order_number)
    public static Specification<Payment> orderNumberEquals(String orderNumber) {
        if (orderNumber == null) return Specification.unrestricted();
        return (root, query, cb) -> cb.equal(root.get("orderNumber"), orderNumber);
    }

    // 결제 시도일이 from 이후 (from 당일 포함)
    public static Specification<Payment> createdFrom(LocalDate from) {
        if (from == null) return Specification.unrestricted();
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from.atStartOfDay());
    }

    // 결제 시도일이 to 이전 (to 당일 포함)
    public static Specification<Payment> createdTo(LocalDate to) {
        if (to == null) return Specification.unrestricted();
        return (root, query, cb) -> cb.lessThan(root.get("createdAt"), to.plusDays(1).atStartOfDay());
    }
}
