package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.shared.order.dto.OrderSummaryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 구매자의 최근 주문 목록 (다른 컨텍스트 공개용)
 */
@Component
@RequiredArgsConstructor
public class OrderGetRecentOrdersUseCase {
    // 주문일 최신순
    private static final Sort LATEST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final OrderRepository orderRepository;

    public List<OrderSummaryDto> getRecentOrders(Long buyerId, int limit) {
        if (limit <= 0) {
            return List.of();
        }

        return orderRepository.findByBuyerId(buyerId, PageRequest.of(0, limit, LATEST)).stream()
                .map(this::toSummary)
                .toList();
    }

    private OrderSummaryDto toSummary(Order order) {
        return new OrderSummaryDto(order.getId(), order.getStatus(), order.getTotalPrice(), order.getCreatedAt());
    }
}
