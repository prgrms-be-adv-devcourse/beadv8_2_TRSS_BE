package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Cart;
import com.backend.boundedContext.order.domain.CartItem;
import com.backend.boundedContext.order.domain.OrderPolicy;
import com.backend.global.exception.DomainException;
import com.backend.shared.product.dto.OptionSnapshotDto;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderUpdateCartItemUseCaseTest {

    @Mock
    OrderSupport orderSupport;          // 가짜: DB 대신 정해 준 장바구니를 돌려준다

    @Mock
    ProductApi productApi;              // 가짜: 상품 모듈 대신 정해 준 옵션 재고를 돌려준다

    OrderUpdateCartItemUseCase useCase; // 진짜: 테스트 대상

    private Cart cart;
    private CartItem item;

    @BeforeEach
    void setUp() {
        // OrderPolicy는 값만 담은 객체라 Mock 대신 진짜로 만든다
        useCase = new OrderUpdateCartItemUseCase(orderSupport, new OrderPolicy(15, 7, 99), productApi);

        cart = Cart.create(3L);
        item = cart.addItem(100L, 2, 99);
        ReflectionTestUtils.setField(item, "id", 1L);
    }

    private void givenStock(int stock) {
        given(productApi.getOptions(List.of(100L))).willReturn(List.of(
                new OptionSnapshotDto(100L, 10L, 2L, "립스틱", "기본", 15000, stock, true, null, null)));
    }

    @Test
    @DisplayName("수량과 선택 여부를 바꾼다")
    void update() {
        // given
        given(orderSupport.findCart(3L)).willReturn(Optional.of(cart));
        givenStock(10);

        // when
        useCase.update(3L, 1L, 5, false);

        // then
        assertThat(item.getQuantity()).isEqualTo(5);
        assertThat(item.isSelected()).isFalse();
    }

    @Test
    @DisplayName("선택 여부만 바꾸면 상품 모듈을 호출하지 않는다")
    void update_onlySelected() {
        given(orderSupport.findCart(3L)).willReturn(Optional.of(cart));

        useCase.update(3L, 1L, null, false);

        assertThat(item.getQuantity()).isEqualTo(2);
        assertThat(item.isSelected()).isFalse();
        verify(productApi, never()).getOptions(any());
    }

    @Test
    @DisplayName("재고보다 많은 수량으로 바꾸면 STOCK_EXCEEDED")
    void update_stockExceeded() {
        given(orderSupport.findCart(3L)).willReturn(Optional.of(cart));
        givenStock(3);

        assertThatThrownBy(() -> useCase.update(3L, 1L, 5, null))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "400-STOCK_EXCEEDED");
        assertThat(item.getQuantity()).isEqualTo(2);   // 기존 수량 유지
    }

    @Test
    @DisplayName("장바구니가 없으면 CART_ITEM_NOT_FOUND")
    void update_noCart() {
        given(orderSupport.findCart(3L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.update(3L, 1L, 5, null))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "404-CART_ITEM_NOT_FOUND");
    }
}
