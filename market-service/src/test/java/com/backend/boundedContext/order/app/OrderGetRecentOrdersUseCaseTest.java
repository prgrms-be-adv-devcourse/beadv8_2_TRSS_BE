package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.order.dto.OrderSummaryDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderGetRecentOrdersUseCaseTest {

    @Mock OrderRepository orderRepository;
    @InjectMocks
    OrderGetRecentOrdersUseCase useCase;

    private static final Long BUYER_ID = 3L;

    private Order order(Long id, LocalDateTime createdAt) {
        Order order = Order.create(BUYER_ID, List.of(
                new OrderItemSpec(1L, 10L, 100L, 2L, "립스틱", "기본", "상점", 10_000, 2)), createdAt, 15);
        ReflectionTestUtils.setField(order, "id", id);
        ReflectionTestUtils.setField(order, "createdAt", createdAt);
        return order;
    }

    @Test
    @DisplayName("limit 개수만큼 최신순으로 조회해 주문 요약(id, 상태, 총액, 주문일)으로 돌려준다")
    void getRecentOrders() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 15, 10, 0);
        given(orderRepository.findByBuyerId(eq(BUYER_ID), any(Pageable.class)))
                .willReturn(List.of(order(500L, createdAt)));

        List<OrderSummaryDto> result = useCase.getRecentOrders(BUYER_ID, 5);

        assertThat(result).containsExactly(
                new OrderSummaryDto(500L, OrderStatus.CREATED, 20_000, createdAt));
        verify(orderRepository).findByBuyerId(eq(BUYER_ID), argThat((Pageable p) ->
                p.getPageNumber() == 0
                        && p.getPageSize() == 5
                        && p.getSort().getOrderFor("createdAt").getDirection() == Sort.Direction.DESC));
    }

    @Test
    @DisplayName("주문이 없으면 빈 목록을 돌려준다")
    void empty() {
        given(orderRepository.findByBuyerId(eq(BUYER_ID), any(Pageable.class))).willReturn(List.of());

        assertThat(useCase.getRecentOrders(BUYER_ID, 5)).isEmpty();
    }

    @Test
    @DisplayName("limit이 0 이하면 조회하지 않고 빈 목록을 돌려준다")
    void nonPositiveLimit() {
        assertThat(useCase.getRecentOrders(BUYER_ID, 0)).isEmpty();
        verifyNoInteractions(orderRepository);
    }
}
