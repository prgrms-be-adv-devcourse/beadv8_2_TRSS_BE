package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Cart;
import com.backend.boundedContext.order.domain.CartItem;
import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderCouponPolicy;
import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.boundedContext.order.domain.OrderPolicy;
import com.backend.boundedContext.order.in.dto.OrderCreateResponse;
import com.backend.boundedContext.order.in.dto.PriceChangedResponse;
import com.backend.boundedContext.order.out.OrderCouponPort;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.out.ProductApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 주문 항목 정가·할인율 저장 (상품 기간 할인 #316 후속, TRSS-222).
 * 정가·할인율은 스냅샷 값을 그대로 저장만 하고, 금액(lineAmount·payAmount·배송비·쿠폰)은 지금처럼 단가(price)만 쓴다.
 */
@ExtendWith(MockitoExtension.class)
class OrderCreateOrderListPriceTest {

    private static final Long BUYER_ID = 3L;
    private static final Long SELLER_ID = 2L;
    private static final Long ORDER_ID = 500L;
    private static final Long OPTION_ID = 100L;
    private static final String CODE = "ABCDEFGH23";

    @Mock OrderSupport orderSupport;
    @Mock OrderRepository orderRepository;
    @Mock ProductApi productApi;
    @Mock MemberApi memberApi;
    @Mock OrderCouponPort orderCouponPort;

    private OrderCreateOrderUseCase useCase(boolean couponEnabled) {
        return new OrderCreateOrderUseCase(orderSupport, orderRepository, new OrderPolicy(15, 7, 99, 100, 1, 2500, 30000),
                productApi, memberApi, new OrderCouponPolicy(couponEnabled), orderCouponPort);
    }

    private OptionSnapshotDto option(long price, long listPrice, Integer discountRate) {
        return new OptionSnapshotDto(OPTION_ID, 10L, SELLER_ID, "립스틱", "기본", price, listPrice, discountRate, 10,
                true, null, null);
    }

    /** 장바구니 항목 1개(옵션 100 x quantity), 현재 옵션 스냅샷 option, 판매자 조회, 저장 시 ID 부여 */
    private void givenCart(OptionSnapshotDto option, int quantity, boolean save) {
        Cart cart = Cart.create(BUYER_ID);
        CartItem item = cart.addItem(OPTION_ID, quantity, 99);
        ReflectionTestUtils.setField(item, "id", 1L);
        given(orderSupport.getCartForUpdate(BUYER_ID)).willReturn(cart);
        given(orderSupport.getSaleableOptions(List.of(OPTION_ID))).willReturn(Map.of(OPTION_ID, option));
        given(memberApi.getSellers(List.of(SELLER_ID))).willReturn(List.of(
                new SellerDto(SELLER_ID, 20L, "테스트상점", SellerStatus.APPROVED)));
        if (save) {
            given(orderRepository.save(any(Order.class))).willAnswer(invocation -> {
                Order order = invocation.getArgument(0);
                ReflectionTestUtils.setField(order, "id", ORDER_ID);
                long itemId = 1;
                for (OrderItem orderItem : order.getItems()) {
                    ReflectionTestUtils.setField(orderItem, "id", itemId++);
                }
                return order;
            });
        }
    }

    private Order savedOrder() {
        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(saved.capture());
        return saved.getValue();
    }

    @Test
    @DisplayName("할인 중 상품: 정가·할인율은 스냅샷 값으로 저장하고, 금액은 할인가(단가)로만 계산한다")
    void discounted() {
        // 정가 20,000원, 25% 할인 → 15,000원 x 2
        givenCart(option(15_000, 20_000, 25), 2, true);

        OrderCreateResponse response = useCase(false).create(BUYER_ID, null, List.of(1L), 30_000, null);

        OrderItem item = savedOrder().getItems().get(0);
        assertThat(item.getUnitPrice()).isEqualTo(15_000);
        assertThat(item.getListUnitPrice()).isEqualTo(20_000);
        assertThat(item.getDiscountRate()).isEqualTo(25);
        // 금액 회귀: 정가가 아니라 단가 기준
        assertThat(item.getLineAmount()).isEqualTo(30_000);
        assertThat(response.totalPrice()).isEqualTo(30_000);
        assertThat(response.shippingFee()).isZero();             // 30,000원 이상 무료 (정가 40,000원 기준이 아님)
        assertThat(response.payAmount()).isEqualTo(30_000);
    }

    @Test
    @DisplayName("할인 없는 상품: 정가 = 단가, 할인율 null")
    void notDiscounted() {
        givenCart(option(15_000, 15_000, null), 1, true);

        useCase(false).create(BUYER_ID, null, List.of(1L), 15_000, null);

        OrderItem item = savedOrder().getItems().get(0);
        assertThat(item.getListUnitPrice()).isEqualTo(item.getUnitPrice()).isEqualTo(15_000);
        assertThat(item.getDiscountRate()).isNull();
    }

    @Test
    @DisplayName("옵션 추가금이 있는 상품: 할인율은 상품 상세의 할인율(스냅샷) 그대로이고 (정가 − 단가)로 다시 계산하지 않는다")
    void additionalPrice() {
        // 상품 10,000원 20% 할인 → 8,000원, 옵션 추가금 5,000원 → 단가 13,000원, 정가 15,000원
        // (15,000 − 13,000) / 15,000 = 13%로 다시 계산하면 상품 상세(20%)와 달라진다
        givenCart(option(13_000, 15_000, 20), 1, true);

        useCase(false).create(BUYER_ID, null, List.of(1L), 13_000, null);

        OrderItem item = savedOrder().getItems().get(0);
        assertThat(item.getUnitPrice()).isEqualTo(13_000);
        assertThat(item.getListUnitPrice()).isEqualTo(15_000);
        assertThat(item.getDiscountRate()).isEqualTo(20);
    }

    @ParameterizedTest(name = "{0}: 예상 {1}원, 현재 {2}원 → 409-PRICE_CHANGED")
    @CsvSource({
            "할인 시작, 20000, 15000, 20000, 25",
            "할인 종료, 15000, 20000, 20000, ",
            "관리자·판매자가 할인을 지금 종료, 15000, 20000, 20000, "
    })
    @DisplayName("의도된 동작: 장바구니를 본 뒤 할인이 시작·종료되면 주문 금액이 달라져 409-PRICE_CHANGED (현재 금액을 알려 주고 저장하지 않는다)")
    void priceChangedAtDiscountBoundary(String when, long expected, long currentPrice, long listPrice, Integer rate) {
        givenCart(option(currentPrice, listPrice, rate), 1, false);

        assertThatThrownBy(() -> useCase(false).create(BUYER_ID, null, List.of(1L), expected, null))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "409-PRICE_CHANGED")
                .hasFieldOrPropertyWithValue("data", new PriceChangedResponse(currentPrice));
        verify(orderRepository, never()).save(any());
        verify(productApi, never()).deductStock(any());
    }

    @Test
    @DisplayName("의도된 동작: 상품 쿠폰은 정가가 아니라 할인가 항목 금액 기준으로 예약한다")
    void productCouponOnDiscountedPrice() {
        // 정가 20,000원 → 할인가 15,000원 x 2 = 30,000원에 상품 쿠폰 5,000원
        givenCart(option(15_000, 20_000, 25), 2, true);
        given(orderCouponPort.reserveProductCoupon(eq(BUYER_ID), eq(CODE), eq(ORDER_ID), eq(1L), eq(10L), eq(30_000L), any()))
                .willReturn(5_000L);

        OrderCreateResponse response = useCase(true).create(BUYER_ID, null, List.of(1L), 25_000, null,   // 예상 금액은 배송비를 뺀 금액
                new OrderCouponSelection(List.of(new OrderCouponSelection.ProductCoupon(1L, CODE)), null));

        verify(orderCouponPort).reserveProductCoupon(eq(BUYER_ID), eq(CODE), eq(ORDER_ID), eq(1L), eq(10L), eq(30_000L), any());
        assertThat(response.totalPrice()).isEqualTo(30_000);
        assertThat(response.productDiscountAmount()).isEqualTo(5_000);
        assertThat(response.payAmount()).isEqualTo(30_000 - 5_000 + 2_500);   // 할인 후 25,000원 < 30,000원이라 배송비
        OrderItem item = savedOrder().getItems().get(0);
        assertThat(item.getListUnitPrice()).isEqualTo(20_000);
        assertThat(item.getDiscountRate()).isEqualTo(25);
    }
}
