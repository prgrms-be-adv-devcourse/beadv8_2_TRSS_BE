package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.in.dto.OrderSummaryResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.page.PageResponse;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.out.ProductApi;
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
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderGetOrdersUseCaseTest {

    @Mock OrderRepository orderRepository;
    @Mock ProductApi productApi;

    @InjectMocks OrderGetOrdersUseCase useCase;

    private static final Long BUYER_ID = 3L;
    private static final Pageable PAGE = PageRequest.of(0, 20);

    /** 상품명마다 항목 한 줄(단가 10,000원, 수량 1)인 주문. 주문 ID와 항목 ID(1부터)를 붙인다 */
    private Order orderWith(Long orderId, String... productNames) {
        return orderAt(LocalDateTime.of(2026, 10, 13, 14, 0), orderId, productNames);
    }

    /** orderedAt에 만든 주문 (결제 만료 15분) */
    private Order orderAt(LocalDateTime orderedAt, Long orderId, String... productNames) {
        List<OrderItemSpec> specs = new ArrayList<>();
        for (int i = 0; i < productNames.length; i++) {
            specs.add(new OrderItemSpec(null, 10L + i, 100L + i, 2L, productNames[i], "기본", "상점", 10_000, 1));
        }
        Order order = Order.create(BUYER_ID, specs, orderedAt, 15);
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
        // 결제 만료 시각이 지난 결제 대기 주문은 남은 시간 0, 결제 전이라 결제일 없음
        assertThat(summary.remainingSeconds()).isZero();
        assertThat(summary.paidAt()).isNull();
    }

    @Test
    @DisplayName("결제 대기 주문은 결제 가능 남은 시간을 담는다")
    @SuppressWarnings("unchecked")
    void remainingSeconds() {
        Order order = orderAt(LocalDateTime.now(), 501L, "립스틱");
        given(orderRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(order), PAGE, 1));

        OrderSummaryResponse summary = useCase.getOrders(BUYER_ID, null, null, null, PAGE).items().get(0);

        assertThat(summary.remainingSeconds()).isBetween(1L, 15 * 60L);
        assertThat(summary.paidAt()).isNull();
    }

    @Test
    @DisplayName("결제 완료 주문은 결제일을 담고 남은 시간은 0이다")
    @SuppressWarnings("unchecked")
    void paidAt() {
        LocalDateTime now = LocalDateTime.now();
        Order order = orderAt(now, 502L, "립스틱");
        order.pay(now);
        given(orderRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(order), PAGE, 1));

        OrderSummaryResponse summary = useCase.getOrders(BUYER_ID, null, null, null, PAGE).items().get(0);

        assertThat(summary.status()).isEqualTo(OrderStatus.PAID);
        assertThat(summary.paidAt()).isEqualTo(now);
        assertThat(summary.remainingSeconds()).isZero();
    }

    @Test
    @DisplayName("대표 상품(첫 항목) 옵션의 현재 대표 이미지를 담고, 상품 조회는 페이지당 한 번만 한다")
    @SuppressWarnings("unchecked")
    void thumbnailUrl() {
        Order withImage = orderWith(500L, "립스틱", "아이섀도");        // 대표 옵션 100
        Order sameOption = orderWith(501L, "립스틱");                  // 대표 옵션 100 (중복 제거)
        given(orderRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(withImage, sameOption), PAGE, 2));
        given(productApi.getOptions(List.of(100L))).willReturn(List.of(
                new OptionSnapshotDto(100L, 10L, 2L, "립스틱", "기본", 10_000, 5, true, null, "https://img/lip.jpg")));

        List<OrderSummaryResponse> items = useCase.getOrders(BUYER_ID, null, null, null, PAGE).items();

        assertThat(items).extracting(OrderSummaryResponse::thumbnailUrl)
                .containsExactly("https://img/lip.jpg", "https://img/lip.jpg");
        verify(productApi).getOptions(List.of(100L));
    }

    @Test
    @DisplayName("상품이 삭제돼 옵션이 조회되지 않거나 이미지가 없으면 대표 이미지는 null이다")
    @SuppressWarnings("unchecked")
    void thumbnailUrlMissing() {
        Order order = orderWith(500L, "립스틱");
        given(orderRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(order), PAGE, 1));
        given(productApi.getOptions(List.of(100L))).willReturn(List.of());

        OrderSummaryResponse summary = useCase.getOrders(BUYER_ID, null, null, null, PAGE).items().get(0);

        assertThat(summary.thumbnailUrl()).isNull();
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
        verifyNoInteractions(productApi);
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
