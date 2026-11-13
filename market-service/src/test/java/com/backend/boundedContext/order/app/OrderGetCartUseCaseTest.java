package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Cart;
import com.backend.boundedContext.order.domain.CartItem;
import com.backend.boundedContext.order.domain.OrderPolicy;
import com.backend.boundedContext.order.domain.UnorderableReason;
import com.backend.boundedContext.order.in.dto.CartItemResponse;
import com.backend.boundedContext.order.in.dto.CartResponse;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.dto.ProductUnsaleableReason;
import com.backend.shared.product.out.ProductApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderGetCartUseCaseTest {

    @Mock OrderSupport orderSupport;
    @Mock ProductApi productApi;   // 상품 모듈 대역
    @Mock MemberApi memberApi;     // 회원 모듈 대역

    OrderGetCartUseCase useCase;

    @BeforeEach
    void setUp() {
        // 배송비 2,500원, 30,000원 이상 무료 (application.yaml과 같은 값)
        useCase = new OrderGetCartUseCase(orderSupport, productApi, memberApi, new OrderPolicy(15, 7, 99, 100, 1, 2500, 30000));
    }

    private static final Long BUYER_ID = 3L;

    /** 옵션 ID = 상품 ID × 10 으로 두고, 판매자는 2번 */
    private OptionSnapshotDto onSale(Long optionId, long price, int stock) {
        return new OptionSnapshotDto(optionId, optionId / 10, 2L, "상품" + optionId, "기본", price, stock,
                true, null, null);
    }

    /** 장바구니에 (옵션 ID, 수량) 순서로 담는다. 항목 ID는 1부터 붙인다 */
    private Cart cartWith(long... optionIdAndQuantity) {
        Cart cart = Cart.create(BUYER_ID);
        long itemId = 1;
        for (int i = 0; i < optionIdAndQuantity.length; i += 2) {
            CartItem item = cart.addItem(optionIdAndQuantity[i], (int) optionIdAndQuantity[i + 1], 99);
            ReflectionTestUtils.setField(item, "id", itemId++);
        }
        return cart;
    }

    @Test
    @DisplayName("장바구니가 없으면 빈 응답을 주고, 상품 모듈을 호출하지 않는다")
    void noCart() {
        given(orderSupport.findCart(BUYER_ID)).willReturn(Optional.empty());

        CartResponse response = useCase.getCart(BUYER_ID);

        assertThat(response.items()).isEmpty();
        assertThat(response.orderable()).isFalse();
        assertThat(response.shippingFee()).isZero();
        assertThat(response.expectedPayAmount()).isZero();
        assertThat(response.remainingForFreeShipping()).isZero();
        verify(productApi, never()).getOptions(any());
    }

    @Test
    @DisplayName("옵션 정보(상품 ID·옵션명)를 응답에 담는다")
    void optionInfo() {
        given(orderSupport.findCart(BUYER_ID)).willReturn(Optional.of(cartWith(100L, 1)));
        given(productApi.getOptions(List.of(100L))).willReturn(List.of(onSale(100L, 15000, 5)));

        CartItemResponse item = useCase.getCart(BUYER_ID).items().get(0);

        assertThat(item.optionId()).isEqualTo(100L);
        assertThat(item.productId()).isEqualTo(10L);
        assertThat(item.optionName()).isEqualTo("기본");
        assertThat(item.orderable()).isTrue();
    }

    @Test
    @DisplayName("판매자 상호명을 응답에 담는다")
    void sellerName() {
        given(orderSupport.findCart(BUYER_ID)).willReturn(Optional.of(cartWith(100L, 1)));
        given(productApi.getOptions(List.of(100L))).willReturn(List.of(onSale(100L, 15000, 5)));
        given(memberApi.getSellers(List.of(2L)))
                .willReturn(List.of(new SellerDto(2L, 20L, "온결", SellerStatus.APPROVED)));

        CartItemResponse item = useCase.getCart(BUYER_ID).items().get(0);

        assertThat(item.sellerName()).isEqualTo("온결");
    }

    @Test
    @DisplayName("판매자 정보가 없으면 상호명만 비우고 조회는 성공한다")
    void sellerMissing() {
        given(orderSupport.findCart(BUYER_ID)).willReturn(Optional.of(cartWith(100L, 1)));
        given(productApi.getOptions(List.of(100L))).willReturn(List.of(onSale(100L, 15000, 5)));
        given(memberApi.getSellers(List.of(2L))).willReturn(List.of());

        CartItemResponse item = useCase.getCart(BUYER_ID).items().get(0);

        assertThat(item.sellerName()).isNull();
        assertThat(item.orderable()).isTrue();
    }

    @Test
    @DisplayName("선택된 주문 가능 항목만 예상 금액에 더한다 (재고 0인 옵션은 품절)")
    void expectedTotalPrice() {
        // given: 100번 15,000원 × 2개, 110번 재고 0(품절) × 1개
        given(orderSupport.findCart(BUYER_ID)).willReturn(Optional.of(cartWith(100L, 2, 110L, 1)));
        given(productApi.getOptions(List.of(100L, 110L))).willReturn(List.of(
                onSale(100L, 15000, 5),
                onSale(110L, 9000, 0)));

        // when
        CartResponse response = useCase.getCart(BUYER_ID);

        // then
        assertThat(response.expectedTotalPrice()).isEqualTo(30000);   // 품절 옵션은 제외
        assertThat(response.selectedCount()).isEqualTo(2);
        assertThat(response.orderable()).isFalse();                  // 선택 항목 중 주문 불가가 있음

        CartItemResponse soldOut = response.items().get(1);
        assertThat(soldOut.orderable()).isFalse();
        assertThat(soldOut.unorderableReason()).isEqualTo(UnorderableReason.SOLD_OUT);
    }

    @Test
    @DisplayName("삭제된 옵션은 OPTION_DELETED")
    void optionDeleted() {
        given(orderSupport.findCart(BUYER_ID)).willReturn(Optional.of(cartWith(100L, 1)));
        given(productApi.getOptions(List.of(100L))).willReturn(List.of(
                new OptionSnapshotDto(100L, 10L, 2L, "립스틱", "기본", 15000, 5,
                        false, ProductUnsaleableReason.OPTION_DELETED, null)));

        CartItemResponse item = useCase.getCart(BUYER_ID).items().get(0);

        assertThat(item.unorderableReason()).isEqualTo(UnorderableReason.OPTION_DELETED);
    }

    @Test
    @DisplayName("옵션 조회 결과에 없는(존재하지 않는) 항목은 PRODUCT_DELETED")
    void notFoundOption() {
        given(orderSupport.findCart(BUYER_ID)).willReturn(Optional.of(cartWith(100L, 1)));
        given(productApi.getOptions(List.of(100L))).willReturn(List.of());   // 상품 모듈이 아무것도 안 돌려줌

        CartItemResponse item = useCase.getCart(BUYER_ID).items().get(0);

        assertThat(item.unorderableReason()).isEqualTo(UnorderableReason.PRODUCT_DELETED);
        assertThat(item.productId()).isNull();
        assertThat(item.productName()).isNull();
        assertThat(item.optionName()).isNull();
        assertThat(item.price()).isZero();
    }

    @Test
    @DisplayName("담은 수량보다 재고가 적으면 STOCK_INSUFFICIENT")
    void stockInsufficient() {
        given(orderSupport.findCart(BUYER_ID)).willReturn(Optional.of(cartWith(100L, 5)));
        given(productApi.getOptions(List.of(100L))).willReturn(List.of(onSale(100L, 15000, 3)));

        CartItemResponse item = useCase.getCart(BUYER_ID).items().get(0);

        assertThat(item.orderable()).isFalse();
        assertThat(item.unorderableReason()).isEqualTo(UnorderableReason.STOCK_INSUFFICIENT);
    }

    @Test
    @DisplayName("선택 항목이 모두 주문 가능하면 orderable = true")
    void allOrderable() {
        given(orderSupport.findCart(BUYER_ID)).willReturn(Optional.of(cartWith(100L, 1, 110L, 3)));
        given(productApi.getOptions(List.of(100L, 110L))).willReturn(List.of(
                onSale(100L, 15000, 5), onSale(110L, 1000, 5)));

        CartResponse response = useCase.getCart(BUYER_ID);

        assertThat(response.orderable()).isTrue();
        assertThat(response.expectedTotalPrice()).isEqualTo(15000 + 3000);
    }

    @Test
    @DisplayName("결제 대기 주문에 들어 있는 옵션은 IN_PENDING_ORDER")
    void inPendingOrder() {
        given(orderSupport.findCart(BUYER_ID)).willReturn(Optional.of(cartWith(100L, 1, 110L, 1)));
        given(productApi.getOptions(List.of(100L, 110L))).willReturn(List.of(
                onSale(100L, 15000, 5),
                onSale(110L, 5000, 5)));
        given(orderSupport.findOptionIdsInPendingOrders(BUYER_ID, List.of(100L, 110L)))
                .willReturn(Set.of(110L));

        CartResponse response = useCase.getCart(BUYER_ID);

        assertThat(response.items().get(0).orderable()).isTrue();
        assertThat(response.items().get(1).unorderableReason()).isEqualTo(UnorderableReason.IN_PENDING_ORDER);
        assertThat(response.orderable()).isFalse();
        assertThat(response.expectedTotalPrice()).isEqualTo(15000);  // 결제 대기 옵션은 금액에서 제외
    }

    @Test
    @DisplayName("배송비: 선택한 주문 가능 항목 합 25,000원이면 배송비 2,500원, 예상 결제 27,500원, 무료 배송까지 5,000원")
    void shippingFee_belowThreshold() {
        // 100번 10,000원 x 2 + 110번 5,000원 x 1 = 25,000원, 120번은 품절이라 합계에서 빠진다
        given(orderSupport.findCart(BUYER_ID)).willReturn(Optional.of(cartWith(100L, 2, 110L, 1, 120L, 1)));
        given(productApi.getOptions(List.of(100L, 110L, 120L))).willReturn(List.of(
                onSale(100L, 10000, 5),
                onSale(110L, 5000, 5),
                onSale(120L, 50000, 0)));

        CartResponse response = useCase.getCart(BUYER_ID);

        assertThat(response.expectedTotalPrice()).isEqualTo(25_000);   // 의미 유지: 배송비 제외
        assertThat(response.shippingFee()).isEqualTo(2_500);
        assertThat(response.expectedPayAmount()).isEqualTo(27_500);
        assertThat(response.remainingForFreeShipping()).isEqualTo(5_000);
    }

    @Test
    @DisplayName("배송비: 선택한 주문 가능 항목 합이 30,000원 이상이면 배송비 0원, 남은 금액 0원")
    void shippingFee_free() {
        given(orderSupport.findCart(BUYER_ID)).willReturn(Optional.of(cartWith(100L, 2)));
        given(productApi.getOptions(List.of(100L))).willReturn(List.of(onSale(100L, 15000, 5)));

        CartResponse response = useCase.getCart(BUYER_ID);

        assertThat(response.expectedTotalPrice()).isEqualTo(30_000);
        assertThat(response.shippingFee()).isZero();
        assertThat(response.expectedPayAmount()).isEqualTo(30_000);
        assertThat(response.remainingForFreeShipping()).isZero();
    }

    @Test
    @DisplayName("배송비: 선택한 항목이 없으면 배송비·예상 결제·남은 금액이 모두 0")
    void shippingFee_noSelection() {
        Cart cart = cartWith(100L, 1);
        cart.selectAll(false);
        given(orderSupport.findCart(BUYER_ID)).willReturn(Optional.of(cart));
        given(productApi.getOptions(List.of(100L))).willReturn(List.of(onSale(100L, 15000, 5)));

        CartResponse response = useCase.getCart(BUYER_ID);

        assertThat(response.selectedCount()).isZero();
        assertThat(response.expectedTotalPrice()).isZero();
        assertThat(response.shippingFee()).isZero();
        assertThat(response.expectedPayAmount()).isZero();
        assertThat(response.remainingForFreeShipping()).isZero();
    }

    @Test
    @DisplayName("배송비: 선택한 항목이 모두 주문 불가(품절)면 배송비·예상 결제·남은 금액이 모두 0")
    void shippingFee_noOrderableSelection() {
        given(orderSupport.findCart(BUYER_ID)).willReturn(Optional.of(cartWith(100L, 1)));
        given(productApi.getOptions(List.of(100L))).willReturn(List.of(onSale(100L, 15000, 0)));

        CartResponse response = useCase.getCart(BUYER_ID);

        assertThat(response.selectedCount()).isEqualTo(1);
        assertThat(response.shippingFee()).isZero();
        assertThat(response.expectedPayAmount()).isZero();
        assertThat(response.remainingForFreeShipping()).isZero();
    }
}
