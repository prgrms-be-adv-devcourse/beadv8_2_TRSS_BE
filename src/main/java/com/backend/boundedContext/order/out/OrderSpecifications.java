package com.backend.boundedContext.order.out;

import com.backend.boundedContext.order.domain.Order;
import com.backend.shared.order.dto.OrderStatus;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;

// 내 주문 목록 검색 조건
public final class OrderSpecifications {

    private OrderSpecifications() {
    }

    public static Specification<Order> buyerEquals(Long buyerId) {
        return (root, query, cb) -> cb.equal(root.get("buyerId"), buyerId);
    }

    public static Specification<Order> statusEquals(OrderStatus status) {
        if (status == null) return Specification.unrestricted();
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    // 주문일이 from 이후 (from 당일 포함)
    public static Specification<Order> orderedFrom(LocalDate from) {
        if (from == null) return Specification.unrestricted();
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from.atStartOfDay());
    }

    // 주문일이 to 이전 (to 당일 포함)
    public static Specification<Order> orderedTo(LocalDate to) {
        if (to == null) return Specification.unrestricted();
        return (root, query, cb) -> cb.lessThan(root.get("createdAt"), to.plusDays(1).atStartOfDay());
    }
}
