package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.boundedContext.order.in.dto.OrderSummaryResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.page.PageResponse;
import com.backend.shared.order.dto.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

import static com.backend.boundedContext.order.out.OrderSpecifications.buyerEquals;
import static com.backend.boundedContext.order.out.OrderSpecifications.orderedFrom;
import static com.backend.boundedContext.order.out.OrderSpecifications.orderedTo;
import static com.backend.boundedContext.order.out.OrderSpecifications.statusEquals;

/**
 * 내 주문 목록
 */
@Component
@RequiredArgsConstructor
public class OrderGetOrdersUseCase {
    // 주문일 최신순
    private static final Sort LATEST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final OrderRepository orderRepository;

    public PageResponse<OrderSummaryResponse> getOrders(Long buyerId, OrderStatus status, LocalDate from, LocalDate to, Pageable pageable) {
        validatePeriod(from, to);

        Specification<Order> spec = Specification.allOf(
                buyerEquals(buyerId),
                statusEquals(status),
                orderedFrom(from),
                orderedTo(to)
        );
        Pageable latestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), LATEST);

        Page<Order> orders = orderRepository.findAll(spec, latestFirst);

        return PageResponse.from(orders, this::toSummary);
    }

    // 조회기간 검증
    private void validatePeriod(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new DomainException(OrderErrorCode.ORDER_PERIOD_INVALID);
        }
    }

    private OrderSummaryResponse toSummary(Order order) {
        List<OrderItem> items = order.getItems();   // @BatchSize: 이 페이지 주문들의 항목을 한 번에 조회

        return new OrderSummaryResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getStatus(),
                order.getTotalPrice(),
                items.get(0).getProductName(),
                items.size(),
                order.getCreatedAt(),
                order.getExpiresAt()
        );
    }
}
