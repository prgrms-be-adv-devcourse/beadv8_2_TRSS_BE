package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Cart;
import com.backend.boundedContext.order.domain.OrderPolicy;
import com.backend.boundedContext.order.in.dto.CartItemAddResponse;
import com.backend.global.exception.DomainException;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderAddCartItemUseCaseTest {

    @Mock OrderSupport orderSupport;
    @Mock ProductApi productApi;
    @Mock MemberApi memberApi;   // 설정하지 않으면 findSellerByMemberId는 Optional.empty() = 판매자 아님

    OrderAddCartItemUseCase useCase;

    private static final Long BUYER_ID = 3L;
    private static final Long OPTION_ID = 100L;

    @BeforeEach
    void setUp() {
        // OrderPolicy는 값만 담은 객체라 Mock 대신 진짜로 만든다
        useCase = new OrderAddCartItemUseCase(orderSupport, new OrderPolicy(15, 7, 99, 100), productApi, memberApi);
    }

    private OptionSnapshotDto onSale(int stock) {
        return new OptionSnapshotDto(OPTION_ID, 10L, 2L, "립스틱", "기본", 15000, stock, true, null, null);
    }

    private OptionSnapshotDto unsaleable(ProductUnsaleableReason reason) {
        return new OptionSnapshotDto(OPTION_ID, 10L, 2L, "립스틱", "기본", 15000, 10, false, reason, null);
    }

    @Test
    @DisplayName("장바구니에 옵션을 담는다")
    void add() {
        Cart cart = Cart.create(BUYER_ID);
        given(productApi.getOptions(List.of(OPTION_ID))).willReturn(List.of(onSale(10)));
        given(orderSupport.getOrCreateCart(BUYER_ID)).willReturn(cart);

        CartItemAddResponse response = useCase.add(BUYER_ID, OPTION_ID, 2);

        assertThat(response.optionId()).isEqualTo(OPTION_ID);
        assertThat(response.quantity()).isEqualTo(2);
        assertThat(cart.getItems()).hasSize(1);
    }

    @Test
    @DisplayName("이미 담긴 옵션이면 수량을 합산한다")
    void add_merge() {
        Cart cart = Cart.create(BUYER_ID);
        cart.addItem(OPTION_ID, 3, 99);
        given(productApi.getOptions(List.of(OPTION_ID))).willReturn(List.of(onSale(10)));
        given(orderSupport.getOrCreateCart(BUYER_ID)).willReturn(cart);

        CartItemAddResponse response = useCase.add(BUYER_ID, OPTION_ID, 2);

        assertThat(response.quantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("존재하지 않는 옵션이면 PRODUCT_NOT_FOUND")
    void productNotFound() {
        given(productApi.getOptions(List.of(OPTION_ID))).willReturn(List.of());

        assertThatThrownBy(() -> useCase.add(BUYER_ID, OPTION_ID, 1))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "404-PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("판매 중지·상품 삭제·옵션 삭제·카테고리 비활성이면 PRODUCT_NOT_ON_SALE")
    void notSaleable() {
        given(productApi.getOptions(List.of(OPTION_ID))).willReturn(
                List.of(unsaleable(ProductUnsaleableReason.PRODUCT_STOPPED)),
                List.of(unsaleable(ProductUnsaleableReason.PRODUCT_DELETED)),
                List.of(unsaleable(ProductUnsaleableReason.OPTION_DELETED)),
                List.of(unsaleable(ProductUnsaleableReason.CATEGORY_INACTIVE)));

        for (int i = 0; i < 4; i++) {   // 호출할 때마다 위 결과를 차례로 돌려준다
            assertThatThrownBy(() -> useCase.add(BUYER_ID, OPTION_ID, 1))
                    .isInstanceOf(DomainException.class)
                    .hasFieldOrPropertyWithValue("resultCode", "409-PRODUCT_NOT_ON_SALE");
        }
        verify(orderSupport, never()).getOrCreateCart(any());   // 장바구니를 만들지 않았는지
    }

    @Test
    @DisplayName("합산 수량이 재고보다 많으면 STOCK_EXCEEDED")
    void stockExceeded() {
        Cart cart = Cart.create(BUYER_ID);
        cart.addItem(OPTION_ID, 4, 99);
        given(productApi.getOptions(List.of(OPTION_ID))).willReturn(List.of(onSale(5)));
        given(orderSupport.getOrCreateCart(BUYER_ID)).willReturn(cart);

        assertThatThrownBy(() -> useCase.add(BUYER_ID, OPTION_ID, 2))   // 4 + 2 > 5
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "400-STOCK_EXCEEDED");
        assertThat(cart.getItems().get(0).getQuantity()).isEqualTo(4);   // 기존 수량 유지
    }

    @Test
    @DisplayName("재고 0인 품절 옵션은 담을 수 없다 (STOCK_EXCEEDED)")
    void soldOut() {
        given(productApi.getOptions(List.of(OPTION_ID))).willReturn(List.of(onSale(0)));
        given(orderSupport.getOrCreateCart(BUYER_ID)).willReturn(Cart.create(BUYER_ID));

        assertThatThrownBy(() -> useCase.add(BUYER_ID, OPTION_ID, 1))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "400-STOCK_EXCEEDED");
    }

    @Test
    @DisplayName("본인이 판매하는 상품이면 SELF_PURCHASE_NOT_ALLOWED")
    void selfPurchase() {
        given(productApi.getOptions(List.of(OPTION_ID))).willReturn(List.of(onSale(10)));
        // 상품의 판매자(2번)가 바로 3번 회원
        given(memberApi.findSellerByMemberId(BUYER_ID)).willReturn(Optional.of(
                new SellerDto(2L, BUYER_ID, "내상점", SellerStatus.APPROVED)));

        assertThatThrownBy(() -> useCase.add(BUYER_ID, OPTION_ID, 1))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "400-SELF_PURCHASE_NOT_ALLOWED");
        verify(orderSupport, never()).getOrCreateCart(any());
    }

    @Test
    @DisplayName("다른 판매자의 상품은 판매자 회원도 담을 수 있다")
    void sellerCanBuyOthers() {
        Cart cart = Cart.create(BUYER_ID);
        given(productApi.getOptions(List.of(OPTION_ID))).willReturn(List.of(onSale(10)));   // 판매자 2번 상품
        given(memberApi.findSellerByMemberId(BUYER_ID)).willReturn(Optional.of(
                new SellerDto(7L, BUYER_ID, "내상점", SellerStatus.APPROVED)));               // 나는 7번 판매자
        given(orderSupport.getOrCreateCart(BUYER_ID)).willReturn(cart);

        CartItemAddResponse response = useCase.add(BUYER_ID, OPTION_ID, 1);

        assertThat(response.quantity()).isEqualTo(1);
    }
}
