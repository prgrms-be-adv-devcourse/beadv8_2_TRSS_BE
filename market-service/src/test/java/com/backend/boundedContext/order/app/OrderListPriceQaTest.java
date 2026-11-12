package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Cart;
import com.backend.boundedContext.order.domain.CartItem;
import com.backend.shared.order.dto.ConfirmType;
import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderCouponPolicy;
import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.domain.OrderPolicy;
import com.backend.boundedContext.order.in.dto.CartItemResponse;
import com.backend.boundedContext.order.in.dto.CartResponse;
import com.backend.boundedContext.order.in.dto.OrderCreateResponse;
import com.backend.boundedContext.order.in.dto.SellerOrderItemResponse;
import com.backend.boundedContext.order.out.OrderCouponPort;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.dto.ConfirmedOrderItemDto;
import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.out.ProductApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * [QA] TRSS-222 정가·할인율 보강: 정가가 금액(배송비 기준·장바구니 합계·정산 후보)에 섞이지 않는지,
 * 옵션 정보가 없는 장바구니 항목, 상품 할인만 있고 쿠폰이 없는 판매자 항목.
 */
@ExtendWith(MockitoExtension.class)
class OrderListPriceQaTest {

    private static final Long BUYER_ID = 3L;
    private static final Long SELLER_ID = 2L;
    private static final Long OPTION_ID = 100L;
    private static final OrderPolicy POLICY = new OrderPolicy(15, 7, 99, 100, 1, 2500, 30000);

    @Mock OrderSupport orderSupport;
    @Mock OrderRepository orderRepository;
    @Mock ProductApi productApi;
    @Mock MemberApi memberApi;
    @Mock OrderCouponPort orderCouponPort;

    private OptionSnapshotDto option(long price, long listPrice, Integer discountRate) {
        return new OptionSnapshotDto(OPTION_ID, 10L, SELLER_ID, "립스틱", "기본", price, listPrice, discountRate, 10,
                true, null, null);
    }

    @Test
    @DisplayName("배송비 기준은 정가가 아니라 단가 합계다: 정가 합계 40,000원(무료 기준 이상)이어도 할인가 합계 28,000원이면 배송비 2,500원")
    void shippingFee_usesUnitPriceNotListPrice() {
        Cart cart = Cart.create(BUYER_ID);
        CartItem cartItem = cart.addItem(OPTION_ID, 2, 99);
        ReflectionTestUtils.setField(cartItem, "id", 1L);
        given(orderSupport.getCartForUpdate(BUYER_ID)).willReturn(cart);
        // 정가 20,000원 30% 할인 → 14,000원 x 2 = 28,000원 (정가 기준이면 40,000원)
        given(orderSupport.getSaleableOptions(List.of(OPTION_ID))).willReturn(Map.of(OPTION_ID, option(14_000, 20_000, 30)));
        given(memberApi.getSellers(List.of(SELLER_ID))).willReturn(List.of(
                new SellerDto(SELLER_ID, 20L, "테스트상점", SellerStatus.APPROVED)));
        given(orderRepository.save(any(Order.class))).willAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            ReflectionTestUtils.setField(order, "id", 500L);
            ReflectionTestUtils.setField(order.getItems().get(0), "id", 1L);
            return order;
        });

        OrderCreateResponse response = new OrderCreateOrderUseCase(orderSupport, orderRepository, POLICY, productApi,
                memberApi, new OrderCouponPolicy(false), orderCouponPort)
                .create(BUYER_ID, null, List.of(1L), 28_000, null);

        assertThat(response.totalPrice()).isEqualTo(28_000);
        assertThat(response.shippingFee()).isEqualTo(2_500);
        assertThat(response.payAmount()).isEqualTo(30_500);
        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(saved.capture());
        assertThat(saved.getValue().getItems().get(0).getListUnitPrice()).isEqualTo(20_000);
    }

    @Test
    @DisplayName("장바구니 합계·배송비·무료까지 남은 금액은 price 기준이고 listPrice는 섞이지 않는다")
    void cartSummary_usesPriceNotListPrice() {
        Cart cart = Cart.create(BUYER_ID);
        ReflectionTestUtils.setField(cart.addItem(OPTION_ID, 2, 99), "id", 1L);
        given(orderSupport.findCart(BUYER_ID)).willReturn(Optional.of(cart));
        given(productApi.getOptions(List.of(OPTION_ID))).willReturn(List.of(option(14_000, 20_000, 30)));
        given(memberApi.getSellers(List.of(SELLER_ID))).willReturn(List.of(
                new SellerDto(SELLER_ID, 20L, "테스트상점", SellerStatus.APPROVED)));

        CartResponse response = new OrderGetCartUseCase(orderSupport, productApi, memberApi, POLICY).getCart(BUYER_ID);

        assertThat(response.expectedTotalPrice()).isEqualTo(28_000);
        assertThat(response.shippingFee()).isEqualTo(2_500);
        assertThat(response.expectedPayAmount()).isEqualTo(30_500);
        assertThat(response.remainingForFreeShipping()).isEqualTo(2_000);
        CartItemResponse item = response.items().get(0);
        assertThat(item.listPrice()).isEqualTo(20_000);
        assertThat(item.discountRate()).isEqualTo(30);
        assertThat(item.lineAmount()).isEqualTo(28_000);
    }

    @Test
    @DisplayName("옵션 조회 결과에 없는 장바구니 항목은 price·listPrice 0, discountRate null (기존 price = 0 동작과 같다)")
    void cart_missingOption() {
        Cart cart = Cart.create(BUYER_ID);
        ReflectionTestUtils.setField(cart.addItem(OPTION_ID, 1, 99), "id", 1L);
        given(orderSupport.findCart(BUYER_ID)).willReturn(Optional.of(cart));
        given(productApi.getOptions(List.of(OPTION_ID))).willReturn(List.of());

        CartItemResponse item = new OrderGetCartUseCase(orderSupport, productApi, memberApi, POLICY)
                .getCart(BUYER_ID).items().get(0);

        assertThat(item.price()).isZero();
        assertThat(item.listPrice()).isZero();
        assertThat(item.discountRate()).isNull();
        assertThat(item.orderable()).isFalse();
    }

    @Test
    @DisplayName("구매 확정 이벤트 항목(정산 후보)은 unitPrice·lineAmount만 담고 정가가 섞이지 않는다")
    void confirmedEvent_usesUnitPrice() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 12, 10, 0);
        Order order = Order.create(BUYER_ID, List.of(
                new OrderItemSpec(null, 10L, OPTION_ID, SELLER_ID, "립스틱", "대용량", "상점", 13_000, 2, 15_000, 20)),
                now, 15);
        ReflectionTestUtils.setField(order, "id", 500L);
        ReflectionTestUtils.setField(order.getItems().get(0), "id", 900L);
        order.pay(now.plusMinutes(1));
        order.confirm(ConfirmType.MANUAL, now.plusDays(1));

        ConfirmedOrderItemDto dto = order.getItems().get(0).toConfirmedOrderItemDto();

        assertThat(dto.unitPrice()).isEqualTo(13_000);
        assertThat(dto.lineAmount()).isEqualTo(26_000);
    }

    @Test
    @DisplayName("상품 기간 할인만 있고 상품 쿠폰이 없는 판매자 항목: discountAmount 0, discountedLineAmount = lineAmount(할인가 기준)")
    void sellerRow_productDiscountIsNotCouponDiscount() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 12, 10, 0);
        Order order = Order.create(BUYER_ID, List.of(
                new OrderItemSpec(null, 10L, OPTION_ID, SELLER_ID, "립스틱", "기본", "상점", 15_000, 2, 20_000, 25)),
                now, 15);
        ReflectionTestUtils.setField(order, "id", 500L);
        OrderItem item = order.getItems().get(0);
        ReflectionTestUtils.setField(item, "id", 900L);
        order.pay(now.plusMinutes(1));

        SellerOrderItemResponse row = SellerOrderItemResponse.from(item);

        assertThat(row.unitPrice()).isEqualTo(15_000);
        assertThat(row.lineAmount()).isEqualTo(30_000);
        assertThat(row.discountAmount()).isZero();
        assertThat(row.discountedLineAmount()).isEqualTo(30_000);
    }
}
