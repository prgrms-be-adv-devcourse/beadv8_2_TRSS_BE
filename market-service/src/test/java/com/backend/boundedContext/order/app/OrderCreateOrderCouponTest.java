package com.backend.boundedContext.order.app;

import com.backend.boundedContext.coupon.domain.CouponErrorCode;
import com.backend.boundedContext.order.domain.Cart;
import com.backend.boundedContext.order.domain.CartItem;
import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.boundedContext.order.domain.OrderPolicy;
import com.backend.boundedContext.order.domain.OrderCouponPolicy;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 주문 생성의 쿠폰 반영 (OrderCouponPolicy 켜짐과 꺼짐)
 */
@ExtendWith(MockitoExtension.class)
class OrderCreateOrderCouponTest {

    private static final Long BUYER_ID = 3L;
    private static final Long SELLER_ID = 2L;
    private static final Long ORDER_ID = 500L;
    private static final String CODE = "ABCDEFGH23";

    @Mock OrderSupport orderSupport;
    @Mock OrderRepository orderRepository;
    @Mock ProductApi productApi;
    @Mock MemberApi memberApi;
    @Mock OrderCouponPort orderCouponPort;

    private OrderCreateOrderUseCase useCase(boolean couponEnabled) {
        return new OrderCreateOrderUseCase(orderSupport, orderRepository, new OrderPolicy(15, 7, 99, 100, 1, 2500, 30000), productApi,
                memberApi, new OrderCouponPolicy(couponEnabled), orderCouponPort);
    }

    /** 옵션 ID = 상품 ID x 10, 판매자는 2번 */
    private OptionSnapshotDto onSale(Long optionId, long price) {
        return new OptionSnapshotDto(optionId, optionId / 10, SELLER_ID, "상품" + optionId, "기본", price, price, null, 10,
                true, null, null);
    }

    /** 장바구니 항목 1: 옵션 100(상품 10) 20,000원 x 2, 항목 2: 옵션 110(상품 11) 15,000원 x 1 → 상품 금액 합계 55,000원 */
    private void givenCartAndOptions() {
        Cart cart = Cart.create(BUYER_ID);
        CartItem first = cart.addItem(100L, 2, 99);
        CartItem second = cart.addItem(110L, 1, 99);
        ReflectionTestUtils.setField(first, "id", 1L);
        ReflectionTestUtils.setField(second, "id", 2L);
        given(orderSupport.getCartForUpdate(BUYER_ID)).willReturn(cart);

        List<OptionSnapshotDto> options = List.of(onSale(100L, 20_000), onSale(110L, 15_000));
        given(orderSupport.getSaleableOptions(List.of(100L, 110L))).willReturn(options.stream()
                .collect(Collectors.toMap(OptionSnapshotDto::optionId, Function.identity())));
        given(memberApi.getSellers(List.of(SELLER_ID))).willReturn(List.of(
                new SellerDto(SELLER_ID, 20L, "테스트상점", SellerStatus.APPROVED)));

        // 저장하면 주문 ID 500, 주문 항목 ID 1부터 (IDENTITY 흉내)
        given(orderRepository.save(any(Order.class))).willAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            ReflectionTestUtils.setField(order, "id", ORDER_ID);
            long itemId = 1;
            for (OrderItem item : order.getItems()) {
                ReflectionTestUtils.setField(item, "id", itemId++);
            }
            return order;
        });
    }

    private OrderCouponSelection productCouponOn(Long cartItemId, Long cartCouponId) {
        return new OrderCouponSelection(List.of(new OrderCouponSelection.ProductCoupon(cartItemId, CODE)), cartCouponId);
    }

    @Test
    @DisplayName("켜짐: 쿠폰을 쓰면 예상 결제 금액을 할인 후 금액과 비교해, 할인 전 금액을 보내면 409-PRICE_CHANGED")
    void expectedPriceIsDiscountedAmount() {
        givenCartAndOptions();
        given(orderCouponPort.reserveProductCoupon(eq(BUYER_ID), eq(CODE), eq(ORDER_ID), eq(1L), eq(10L), eq(40_000L), any()))
                .willReturn(30_000L);
        given(orderCouponPort.reserveCartCoupon(eq(BUYER_ID), eq(7L), eq(ORDER_ID), eq(25_000L), any())).willReturn(3_000L);

        assertThatThrownBy(() -> useCase(true).create(BUYER_ID, null, List.of(1L, 2L), 55_000, null,
                productCouponOn(1L, 7L)))
                .hasFieldOrPropertyWithValue("resultCode", "409-PRICE_CHANGED")
                .hasFieldOrPropertyWithValue("data", new PriceChangedResponse(22_000));   // 쿠폰 할인 후 실제 결제 금액
    }

    @Test
    @DisplayName("켜짐: 상품 쿠폰을 지정한 항목에 예약하고, 할인 후 금액으로 장바구니 쿠폰을 예약해 결제 금액을 만든다")
    void productAndCartCoupon() {
        givenCartAndOptions();
        // 항목 1(40,000원)에 상품 쿠폰 30,000원 → 할인 후 25,000원 기준으로 장바구니 쿠폰 3,000원
        given(orderCouponPort.reserveProductCoupon(eq(BUYER_ID), eq(CODE), eq(ORDER_ID), eq(1L), eq(10L), eq(40_000L), any()))
                .willReturn(30_000L);
        given(orderCouponPort.reserveCartCoupon(eq(BUYER_ID), eq(7L), eq(ORDER_ID), eq(25_000L), any())).willReturn(3_000L);

        OrderCreateResponse response = useCase(true).create(BUYER_ID, null, List.of(1L, 2L), 22_000, null,
                productCouponOn(1L, 7L));

        assertThat(response.totalPrice()).isEqualTo(55_000);
        assertThat(response.productDiscountAmount()).isEqualTo(30_000);
        assertThat(response.cartCouponDiscountAmount()).isEqualTo(3_000);
        // 상품 쿠폰 할인 후 25,000원 < 30,000원이라 배송비 2,500원
        assertThat(response.shippingFee()).isEqualTo(2_500);
        assertThat(response.payAmount()).isEqualTo(55_000 - 30_000 - 3_000 + 2_500);
        assertThat(response.items()).extracting(OrderCreateResponse.Item::discountAmount).containsExactly(30_000L, 0L);

        // 재고 차감 → 상품 쿠폰 → 장바구니 쿠폰 순서
        InOrder order = inOrder(productApi, orderCouponPort);
        order.verify(productApi).deductStock(any());
        order.verify(orderCouponPort).reserveProductCoupon(any(), any(), any(), any(), any(), anyLong(), any());
        order.verify(orderCouponPort).reserveCartCoupon(any(), any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("켜짐: 쿠폰 없이 주문하면 쿠폰 쪽을 부르지 않고 결제 금액은 상품 금액 합계와 같다")
    void noCoupon() {
        givenCartAndOptions();

        OrderCreateResponse response = useCase(true).create(BUYER_ID, null, List.of(1L, 2L), 55_000, null,
                OrderCouponSelection.NONE);

        assertThat(response.shippingFee()).isZero();   // 55,000원 ≥ 30,000원
        assertThat(response.payAmount()).isEqualTo(55_000);
        verifyNoInteractions(orderCouponPort);
    }

    @Test
    @DisplayName("켜짐: 상품 쿠폰만 쓰면 장바구니 쿠폰은 예약하지 않는다")
    void productCouponOnly() {
        givenCartAndOptions();
        given(orderCouponPort.reserveProductCoupon(any(), any(), any(), any(), any(), anyLong(), any())).willReturn(26_000L);

        OrderCreateResponse response = useCase(true).create(BUYER_ID, null, List.of(1L, 2L), 29_000, null,
                productCouponOn(1L, null));

        assertThat(response.productDiscountAmount()).isEqualTo(26_000);
        assertThat(response.cartCouponDiscountAmount()).isZero();
        assertThat(response.shippingFee()).isEqualTo(2_500);   // 상품 쿠폰 할인 후 29,000원
        assertThat(response.payAmount()).isEqualTo(29_000 + 2_500);
        verify(orderCouponPort, never()).reserveCartCoupon(any(), any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("켜짐: 쿠폰 예약이 실패하면 예외가 그대로 나가 주문 저장과 재고 차감이 함께 롤백된다")
    void reservationFails() {
        givenCartAndOptions();
        given(orderCouponPort.reserveProductCoupon(any(), any(), any(), any(), any(), anyLong(), any()))
                .willThrow(new DomainException(CouponErrorCode.COUPON_SOLD_OUT));

        assertThatThrownBy(() -> useCase(true).create(BUYER_ID, null, List.of(1L, 2L), 55_000, null,
                productCouponOn(1L, 7L)))
                .extracting("resultCode").isEqualTo("409-COUPON_SOLD_OUT");
        verify(orderCouponPort, never()).reserveCartCoupon(any(), any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("꺼짐: 새 주문에 쿠폰을 보내면 재고를 건드리기 전에 409-ORDER_COUPON_NOT_AVAILABLE")
    void disabledWithCoupon() {
        given(orderSupport.getCartForUpdate(BUYER_ID)).willReturn(Cart.create(BUYER_ID));

        assertThatThrownBy(() -> useCase(false).create(BUYER_ID, null, List.of(1L), 40_000, null,
                new OrderCouponSelection(List.of(), 7L)))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-ORDER_COUPON_NOT_AVAILABLE");

        verifyNoInteractions(orderRepository, productApi, orderCouponPort);
    }

    @Test
    @DisplayName("꺼짐: 쿠폰을 쓴 주문을 만든 뒤 설정이 꺼져도, 같은 Idempotency-Key 재시도는 409 대신 처음 만든 주문을 돌려준다")
    void disabledRetryReturnsExistingOrder() {
        String key = "order-key-1";
        given(orderSupport.getCartForUpdate(BUYER_ID)).willReturn(Cart.create(BUYER_ID));
        Order existing = Order.create(BUYER_ID, key, List.of(
                        new OrderItemSpec(1L, 10L, 100L, 2L, "립스틱", "기본", "상점", 40_000, 1)),
                LocalDateTime.now(), 15);
        ReflectionTestUtils.setField(existing, "id", ORDER_ID);
        given(orderRepository.findByBuyerIdAndIdempotencyKey(BUYER_ID, key)).willReturn(Optional.of(existing));

        OrderCreateResponse response = useCase(false).create(BUYER_ID, key, List.of(1L), 40_000, null,
                new OrderCouponSelection(List.of(), 7L));

        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        verifyNoInteractions(productApi, orderCouponPort);
    }

    @Test
    @DisplayName("꺼짐: 쿠폰 없이 주문하면 예상 금액은 상품 금액 합계와 비교하고(지금 동작 그대로), 결제 금액은 여기에 배송비를 더한다")
    void disabledWithoutCoupon() {
        Cart cart = Cart.create(BUYER_ID);
        CartItem item = cart.addItem(100L, 1, 99);
        ReflectionTestUtils.setField(item, "id", 1L);
        given(orderSupport.getCartForUpdate(BUYER_ID)).willReturn(cart);
        given(orderSupport.getSaleableOptions(List.of(100L))).willReturn(Arrays.stream(new OptionSnapshotDto[]{onSale(100L, 10_000)})
                .collect(Collectors.toMap(OptionSnapshotDto::optionId, Function.identity())));
        given(memberApi.getSellers(List.of(SELLER_ID))).willReturn(List.of(
                new SellerDto(SELLER_ID, 20L, "테스트상점", SellerStatus.APPROVED)));
        given(orderRepository.save(any(Order.class))).willAnswer(invocation -> invocation.getArgument(0));

        OrderCreateResponse response = useCase(false).create(BUYER_ID, null, List.of(1L), 10_000, null);

        assertThat(response.totalPrice()).isEqualTo(10_000);
        assertThat(response.shippingFee()).isEqualTo(2_500);
        assertThat(response.payAmount()).isEqualTo(12_500);
        verifyNoInteractions(orderCouponPort);
    }

    // ===== 배송비 (TRSS-210) =====

    /** 장바구니 항목 1: 옵션 100 priceA x 1, 항목 2: 옵션 110 priceB x 1 (같은 판매자). 저장하면 주문 ID 500, 항목 ID 1부터 */
    private void givenTwoItems(long priceA, long priceB) {
        Cart cart = Cart.create(BUYER_ID);
        CartItem first = cart.addItem(100L, 1, 99);
        CartItem second = cart.addItem(110L, 1, 99);
        ReflectionTestUtils.setField(first, "id", 1L);
        ReflectionTestUtils.setField(second, "id", 2L);
        given(orderSupport.getCartForUpdate(BUYER_ID)).willReturn(cart);

        List<OptionSnapshotDto> options = List.of(onSale(100L, priceA), onSale(110L, priceB));
        given(orderSupport.getSaleableOptions(List.of(100L, 110L))).willReturn(options.stream()
                .collect(Collectors.toMap(OptionSnapshotDto::optionId, Function.identity())));
        given(memberApi.getSellers(List.of(SELLER_ID))).willReturn(List.of(
                new SellerDto(SELLER_ID, 20L, "테스트상점", SellerStatus.APPROVED)));
        given(orderRepository.save(any(Order.class))).willAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            ReflectionTestUtils.setField(order, "id", ORDER_ID);
            long itemId = 1;
            for (OrderItem item : order.getItems()) {
                ReflectionTestUtils.setField(item, "id", itemId++);
            }
            return order;
        });
    }

    @Test
    @DisplayName("배송비: 쿠폰 주문은 배송비를 뺀 할인 후 금액을 보내면 통과한다 (배송비 때문에 PRICE_CHANGED가 나지 않는다)")
    void shippingFee_couponOrderComparesBeforeShipping() {
        // 32,000원 + 상품 쿠폰 3,000원 → 배송비 기준 29,000원 → 배송비 2,500원
        givenTwoItems(20_000, 12_000);
        given(orderCouponPort.reserveProductCoupon(eq(BUYER_ID), eq(CODE), eq(ORDER_ID), eq(1L), eq(10L), eq(20_000L), any()))
                .willReturn(3_000L);

        OrderCreateResponse response = useCase(true).create(BUYER_ID, null, List.of(1L, 2L), 29_000, null,
                productCouponOn(1L, null));

        assertThat(response.shippingFee()).isEqualTo(2_500);
        assertThat(response.payAmount()).isEqualTo(31_500);
    }

    @Test
    @DisplayName("배송비: 쿠폰 주문에 배송비를 더한 금액을 보내면 409-PRICE_CHANGED이고 currentAmount는 배송비 제외 금액이다")
    void shippingFee_couponOrderWithShippingIsPriceChanged() {
        givenTwoItems(20_000, 12_000);
        given(orderCouponPort.reserveProductCoupon(any(), any(), any(), any(), any(), anyLong(), any())).willReturn(3_000L);

        assertThatThrownBy(() -> useCase(true).create(BUYER_ID, null, List.of(1L, 2L), 31_500, null,
                productCouponOn(1L, null)))
                .hasFieldOrPropertyWithValue("resultCode", "409-PRICE_CHANGED")
                .hasFieldOrPropertyWithValue("data", new PriceChangedResponse(29_000));
    }

    @Test
    @DisplayName("배송비: 35,000원 + 장바구니 쿠폰 5,000원 → 장바구니 쿠폰은 기준에 영향이 없어 배송비 0원, 결제 금액 30,000원")
    void shippingFee_cartCouponDoesNotAffect() {
        givenTwoItems(20_000, 15_000);
        given(orderCouponPort.reserveCartCoupon(eq(BUYER_ID), eq(7L), eq(ORDER_ID), eq(35_000L), any())).willReturn(5_000L);

        OrderCreateResponse response = useCase(true).create(BUYER_ID, null, List.of(1L, 2L), 30_000, null,
                new OrderCouponSelection(List.of(), 7L));

        assertThat(response.cartCouponDiscountAmount()).isEqualTo(5_000);
        assertThat(response.shippingFee()).isZero();
        assertThat(response.payAmount()).isEqualTo(30_000);
    }
}
