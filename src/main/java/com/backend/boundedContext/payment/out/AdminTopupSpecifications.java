package com.backend.boundedContext.payment.out;

import java.time.LocalDate;

import org.springframework.data.jpa.domain.Specification;

import com.backend.boundedContext.payment.domain.Topup;

// 관리자 결제 조회 충전 탭 검색 조건. 조건이 없으면(null) 걸지 않음
public final class AdminTopupSpecifications {

    private AdminTopupSpecifications() {
    }

    public static Specification<Topup> memberEquals(Long memberId) {
        if (memberId == null) return Specification.unrestricted();
        return (root, query, cb) -> cb.equal(root.get("memberId"), memberId);
    }

    // 충전번호(토스 주문번호) 정확히 일치
    public static Specification<Topup> orderIdEquals(String orderId) {
        if (orderId == null) return Specification.unrestricted();
        return (root, query, cb) -> cb.equal(root.get("tossOrderId"), orderId);
    }

    // 충전 준비일이 from 이후 (from 당일 포함)
    public static Specification<Topup> createdFrom(LocalDate from) {
        if (from == null) return Specification.unrestricted();
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from.atStartOfDay());
    }

    // 충전 준비일이 to 이전 (to 당일 포함)
    public static Specification<Topup> createdTo(LocalDate to) {
        if (to == null) return Specification.unrestricted();
        return (root, query, cb) -> cb.lessThan(root.get("createdAt"), to.plusDays(1).atStartOfDay());
    }
}
