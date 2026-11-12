package com.backend.boundedContext.order.out;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.ReturnFilter;
import com.backend.shared.order.dto.OrderStatus;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.Collection;

// 주문 목록 검색 조건 (내 주문 목록, 관리자 반품 목록)
public final class OrderSpecifications {

    private OrderSpecifications() {
    }

    public static Specification<Order> buyerEquals(Long buyerId) {
        return (root, query, cb) -> cb.equal(root.get("buyerId"), buyerId);
    }

    // 상태가 목록 중 하나 (비어 있으면 조건 없음)
    public static Specification<Order> statusIn(Collection<OrderStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) return Specification.unrestricted();
        return (root, query, cb) -> root.get("status").in(statuses);
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

    // 관리자 반품 목록: 반품을 요청한 주문 중 필터에 맞는 것 (인덱스 idx_market_order_return_requested의 조건과 같음)
    public static Specification<Order> returnFilter(ReturnFilter filter) {
        Specification<Order> requested = (root, query, cb) -> cb.isNotNull(root.get("returnRequestedAt"));
        return switch (filter) {
            case REQUESTED -> requested.and((root, query, cb) -> cb.equal(root.get("status"), OrderStatus.RETURN_REQUESTED));
            case RETURNED -> requested.and((root, query, cb) -> cb.equal(root.get("status"), OrderStatus.RETURNED));
            case REJECTED -> requested.and((root, query, cb) -> cb.isNotNull(root.get("returnRejectedAt")));
            case ALL -> requested;
        };
    }
}
