package com.backend.boundedContext.order.out;

import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.shared.order.dto.OrderStatus;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * 주문 항목 조회 조건 (판매자 주문 항목 조회)
 */
public final class OrderItemSpecifications {

    private OrderItemSpecifications() {
    }

    public static Specification<OrderItem> sellerEquals(Long sellerId) {
        return (root, query, cb) -> cb.equal(root.get("sellerId"), sellerId);
    }

    // 상태
    public static Specification<OrderItem> statusIn(OrderStatus status, Collection<OrderStatus> visibleStatuses) {
        Collection<OrderStatus> statuses = status == null ? visibleStatuses : List.of(status);
        return (root, query, cb) -> root.get("order").get("status").in(statuses);
    }

    // 주문일이 from 이후
    public static Specification<OrderItem> orderedFrom(LocalDate from) {
        if (from == null) return Specification.unrestricted();
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("order").get("createdAt"), from.atStartOfDay());
    }

    // 주문일이 to 이전
    public static Specification<OrderItem> orderedTo(LocalDate to) {
        if (to == null) return Specification.unrestricted();
        return (root, query, cb) -> cb.lessThan(root.get("order").get("createdAt"), to.plusDays(1).atStartOfDay());
    }

    // 주문번호 또는 상품명
    public static Specification<OrderItem> keywordContains(String keyword) {
        if (keyword == null || keyword.isBlank()) return Specification.unrestricted();
        String like = "%" + keyword.trim() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(root.get("order").get("orderNumber"), like),
                cb.like(root.get("productName"), like)
        );
    }
}
