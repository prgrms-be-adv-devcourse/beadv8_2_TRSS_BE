package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.in.dto.OrderSummaryResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.page.PageResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderGetOrdersUseCaseTest {

    @Mock OrderRepository orderRepository;

    @InjectMocks OrderGetOrdersUseCase useCase;

    private static final Long BUYER_ID = 3L;
    private static final Pageable PAGE = PageRequest.of(0, 20);

    /** 상품명마다 항목 한 줄(단가 10,000원, 수량 1)인 주문. 주문 ID와 항목 ID(1부터)를 붙인다 */
    private Order orderWith(Long orderId, String... productNames) {
        List<OrderItemSpec> specs = new ArrayList<>();
        for (int i = 0; i < productNames.length; i++) {
            specs.add(new OrderItemSpec(null, 10L + i, 100L + i, 2L, productNames[i], "기본", "상점", 10_000, 1));
        }
        Order order = Order.create(BUYER_ID, specs, LocalDateTime.of(2026, 10, 13, 14, 0), 15);
        ReflectionTestUtils.setField(order, "id", orderId);
        long itemId = 1;
        for (OrderItem item : order.getItems()) {
            ReflectionTestUtils.setField(item, "id", itemId++);
        }
        return order;
    }

    @Test
    @DisplayName("주문마다 대표 상품명(첫 항목)과 항목 수를 담는다")
    @SuppressWarnings("unchecked")
    void summary() {
        Order order = orderWith(500L, "립스틱", "아이섀도", "블러셔");
        given(orderRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(order), PAGE, 1));

        PageResponse<OrderSummaryResponse> response = useCase.getOrders(BUYER_ID, null, null, null, PAGE);

        assertThat(response.totalElements()).isEqualTo(1);
        OrderSummaryResponse summary = response.items().get(0);
        assertThat(summary.orderId()).isEqualTo(500L);
        assertThat(summary.representativeProductName()).isEqualTo("립스틱");
        assertThat(summary.itemCount()).isEqualTo(3);
        assertThat(summary.totalPrice()).isEqualTo(30_000);
    }

    @Test
    @DisplayName("요청한 정렬과 관계없이 주문일 최신순으로 조회한다")
    @SuppressWarnings("unchecked")
    void latestFirst() {
        given(orderRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(), PAGE, 0));

        useCase.getOrders(BUYER_ID, null, null, null, PageRequest.of(1, 10, Sort.by("totalPrice")));

        verify(orderRepository).findAll(any(Specification.class), argThat((Pageable p) ->
                p.getPageNumber() == 1
                        && p.getPageSize() == 10
                        && p.getSort().equals(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))));
    }

    @Test
    @DisplayName("주문이 없으면 빈 목록을 돌려준다")
    @SuppressWarnings("unchecked")
    void empty() {
        given(orderRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(), PAGE, 0));

        PageResponse<OrderSummaryResponse> response = useCase.getOrders(BUYER_ID, null, null, null, PAGE);

        assertThat(response.items()).isEmpty();
        assertThat(response.totalElements()).isZero();
    }

    @Test
    @DisplayName("시작일이 종료일보다 늦으면 ORDER_PERIOD_INVALID")
    void periodInvalid() {
        assertThatThrownBy(() -> useCase.getOrders(BUYER_ID, null,
                LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 1), PAGE))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "400-ORDER_PERIOD_INVALID");
    }
}
