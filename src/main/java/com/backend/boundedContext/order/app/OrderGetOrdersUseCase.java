package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.boundedContext.order.in.dto.OrderSummaryResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.page.PageResponse;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.out.ProductApi;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.backend.boundedContext.order.out.OrderSpecifications.buyerEquals;
import static com.backend.boundedContext.order.out.OrderSpecifications.orderedFrom;
import static com.backend.boundedContext.order.out.OrderSpecifications.orderedTo;
import static com.backend.boundedContext.order.out.OrderSpecifications.statusIn;

/**
 * 내 주문 목록
 */
@Component
@RequiredArgsConstructor
public class OrderGetOrdersUseCase {
    // 주문일 최신순
    private static final Sort LATEST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final OrderRepository orderRepository;
    private final ProductApi productApi;

    public PageResponse<OrderSummaryResponse> getOrders(Long buyerId, List<OrderStatus> statuses, LocalDate from, LocalDate to, Pageable pageable) {
        validatePeriod(from, to);
        LocalDateTime now = LocalDateTime.now();

        Specification<Order> spec = Specification.allOf(
                buyerEquals(buyerId),
                statusIn(statuses),
                orderedFrom(from),
                orderedTo(to)
        );
        Pageable latestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), LATEST);

        Page<Order> orders = orderRepository.findAll(spec, latestFirst);
        Map<Long, String> thumbnails = getThumbnailUrls(orders.getContent());

        return PageResponse.from(orders, order -> toSummary(order, now, thumbnails));
    }

    // 조회기간 검증
    private void validatePeriod(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new DomainException(OrderErrorCode.ORDER_PERIOD_INVALID);
        }
    }

    // 대표 상품 옵션 ID → 현재 대표 이미지 URL
    private Map<Long, String> getThumbnailUrls(List<Order> orders) {
        List<Long> optionIds = orders.stream()
                .map(order -> representative(order).getOptionId())
                .distinct()
                .toList();
        if (optionIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, String> thumbnails = new HashMap<>();
        for (OptionSnapshotDto option : productApi.getOptions(optionIds)) {
            thumbnails.put(option.optionId(), option.thumbnailUrl());
        }
        return thumbnails;
    }

    // 대표 항목: 첫 번째 주문 항목
    private OrderItem representative(Order order) {
        return order.getItems().get(0);
    }

    private OrderSummaryResponse toSummary(Order order, LocalDateTime now, Map<Long, String> thumbnails) {
        List<OrderItem> items = order.getItems();
        OrderItem first = representative(order);

        return new OrderSummaryResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getStatus(),
                order.getTotalPrice(),
                first.getProductName(),
                thumbnails.get(first.getOptionId()),
                items.size(),
                order.getCreatedAt(),
                order.getExpiresAt(),
                order.remainingPaymentSeconds(now),
                order.getPaidAt()
        );
    }
}
