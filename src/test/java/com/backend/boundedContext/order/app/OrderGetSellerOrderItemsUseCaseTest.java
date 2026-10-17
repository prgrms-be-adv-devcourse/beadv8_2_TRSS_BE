package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.in.dto.SellerOrderItemResponse;
import com.backend.boundedContext.order.out.OrderItemRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.page.PageResponse;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.dto.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderGetSellerOrderItemsUseCaseTest {

    @Mock OrderItemRepository orderItemRepository;
    @Mock MemberApi memberApi;

    @InjectMocks OrderGetSellerOrderItemsUseCase useCase;

    private static final Long MEMBER_ID = 20L;
    private static final Long SELLER_ID = 2L;
    private static final Pageable PAGE = PageRequest.of(0, 20);
    private static final LocalDateTime ORDERED_AT = LocalDateTime.of(2026, 10, 13, 14, 0);

    private SellerDto seller(SellerStatus status) {
        return new SellerDto(SELLER_ID, MEMBER_ID, "온결", status);
    }

    /** 결제 완료된 주문의 항목 하나 (옵션 100, 12,000원 × 3) */
    private OrderItem paidItem() {
        Order order = Order.create(3L, List.of(
                new OrderItemSpec(null, 10L, 100L, SELLER_ID, "약산성 토너", "300ml", "온결", 12_000, 3)), ORDERED_AT, 15);
        ReflectionTestUtils.setField(order, "id", 500L);
        order.pay(ORDERED_AT.plusMinutes(1));
        OrderItem item = order.getItems().get(0);
        ReflectionTestUtils.setField(item, "id", 900L);
        return item;
    }

    @Test
    @DisplayName("주문 항목 스냅샷(상품명·옵션명·단가·금액)과 주문 정보(번호·주문일·상태)를 담는다")
    @SuppressWarnings("unchecked")
    void response() {
        OrderItem item = paidItem();
        given(memberApi.findSellerByMemberId(MEMBER_ID)).willReturn(Optional.of(seller(SellerStatus.APPROVED)));
        given(orderItemRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(item), PAGE, 1));

        PageResponse<SellerOrderItemResponse> response =
                useCase.getSellerOrderItems(MEMBER_ID, null, null, null, null, PAGE);

        assertThat(response.totalElements()).isEqualTo(1);
        SellerOrderItemResponse row = response.items().get(0);
        assertThat(row.orderItemId()).isEqualTo(900L);
        assertThat(row.orderId()).isEqualTo(500L);
        assertThat(row.orderNumber()).isEqualTo(item.getOrder().getOrderNumber());
        assertThat(row.orderedAt()).isEqualTo(item.getOrder().getCreatedAt());
        assertThat(row.productId()).isEqualTo(10L);
        assertThat(row.productName()).isEqualTo("약산성 토너");
        assertThat(row.optionName()).isEqualTo("300ml");
        assertThat(row.quantity()).isEqualTo(3);
        assertThat(row.unitPrice()).isEqualTo(12_000);
        assertThat(row.lineAmount()).isEqualTo(36_000);
        assertThat(row.status()).isEqualTo(OrderStatus.PAID);
    }

    @ParameterizedTest(name = "{0} 판매자도 지난 주문 항목을 조회할 수 있다")
    @EnumSource(value = SellerStatus.class, names = {"APPROVED", "WITHDRAW_REQUESTED", "WITHDRAWN", "SANCTIONED"})
    @SuppressWarnings("unchecked")
    void anySellerStatus(SellerStatus status) {
        given(memberApi.findSellerByMemberId(MEMBER_ID)).willReturn(Optional.of(seller(status)));
        given(orderItemRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(paidItem()), PAGE, 1));

        PageResponse<SellerOrderItemResponse> response =
                useCase.getSellerOrderItems(MEMBER_ID, null, null, null, null, PAGE);

        assertThat(response.items()).hasSize(1);
    }

    @Test
    @DisplayName("판매자 정보가 없으면 조회하지 않고 빈 목록을 돌려준다")
    void noSeller() {
        given(memberApi.findSellerByMemberId(MEMBER_ID)).willReturn(Optional.empty());

        PageResponse<SellerOrderItemResponse> response =
                useCase.getSellerOrderItems(MEMBER_ID, null, null, null, null, PAGE);

        assertThat(response.items()).isEmpty();
        assertThat(response.totalElements()).isZero();
        verifyNoInteractions(orderItemRepository);
    }

    @Test
    @DisplayName("요청한 정렬과 관계없이 주문일 최신순, 같은 주문 안에서는 항목 id 역순으로 조회한다")
    @SuppressWarnings("unchecked")
    void latestFirst() {
        given(memberApi.findSellerByMemberId(MEMBER_ID)).willReturn(Optional.of(seller(SellerStatus.APPROVED)));
        given(orderItemRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(), PAGE, 0));

        useCase.getSellerOrderItems(MEMBER_ID, null, null, null, null, PageRequest.of(1, 10, Sort.by("unitPrice")));

        verify(orderItemRepository).findAll(any(Specification.class), argThat((Pageable p) ->
                p.getPageNumber() == 1
                        && p.getPageSize() == 10
                        && p.getSort().equals(Sort.by(Sort.Order.desc("order.createdAt"), Sort.Order.desc("id")))));
    }

    @ParameterizedTest(name = "{0} 상태로는 조회할 수 없다 (ORDER_STATUS_INVALID)")
    @EnumSource(value = OrderStatus.class, names = {"CREATED", "PAYMENT_FAILED", "CANCELED"})
    void statusInvalid(OrderStatus status) {
        assertThatThrownBy(() -> useCase.getSellerOrderItems(MEMBER_ID, status, null, null, null, PAGE))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "400-ORDER_STATUS_INVALID");
        verifyNoInteractions(memberApi, orderItemRepository);
    }

    @Test
    @DisplayName("시작일이 종료일보다 늦으면 ORDER_PERIOD_INVALID")
    void periodInvalid() {
        assertThatThrownBy(() -> useCase.getSellerOrderItems(MEMBER_ID, null,
                LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 1), null, PAGE))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "400-ORDER_PERIOD_INVALID");
        verifyNoInteractions(memberApi, orderItemRepository);
    }
}
