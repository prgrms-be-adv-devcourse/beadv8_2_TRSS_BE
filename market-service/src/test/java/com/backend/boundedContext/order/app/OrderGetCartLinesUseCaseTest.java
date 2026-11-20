package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Cart;
import com.backend.boundedContext.order.domain.CartItem;
import com.backend.shared.order.dto.CartLineDto;
import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.dto.ProductUnsaleableReason;
import com.backend.shared.product.out.ProductApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderGetCartLinesUseCaseTest {

    private static final Long BUYER_ID = 3L;

    @Mock OrderSupport orderSupport;
    @Mock ProductApi productApi;

    @InjectMocks OrderGetCartLinesUseCase useCase;

    private Cart cart() {
        Cart cart = Cart.create(BUYER_ID);
        CartItem first = cart.addItem(100L, 2, 99);
        CartItem second = cart.addItem(200L, 1, 99);
        CartItem third = cart.addItem(300L, 1, 99);
        ReflectionTestUtils.setField(first, "id", 1L);
        ReflectionTestUtils.setField(second, "id", 2L);
        ReflectionTestUtils.setField(third, "id", 3L);
        return cart;
    }

    @Test
    @DisplayName("보낸 순서대로 항목마다 상품, 현재 단가, 수량을 주고 판매할 수 없거나 없어진 옵션은 0원, saleable=false다")
    void lines() {
        given(orderSupport.findCart(BUYER_ID)).willReturn(Optional.of(cart()));
        given(productApi.getOptions(List.of(300L, 100L, 200L))).willReturn(List.of(
                new OptionSnapshotDto(100L, 10L, 2L, "립스틱", "기본", 20_000, 20_000, null, 5, true, null, null),
                new OptionSnapshotDto(200L, 20L, 2L, "섀도", "기본", 30_000, 30_000, null, 5, false, ProductUnsaleableReason.PRODUCT_STOPPED, null)));

        List<CartLineDto> lines = useCase.getLines(BUYER_ID, List.of(3L, 1L, 2L, 1L));

        assertThat(lines).containsExactly(
                new CartLineDto(3L, null, 0, 1, false),
                new CartLineDto(1L, 10L, 20_000, 2, true),
                new CartLineDto(2L, 20L, 0, 1, false));
        assertThat(lines.get(1).lineAmount()).isEqualTo(40_000);
    }

    @Test
    @DisplayName("내 장바구니 항목이 아니면 404-CART_ITEM_NOT_FOUND, 항목을 고르지 않으면 조회 없이 빈 목록")
    void notMineOrEmpty() {
        given(orderSupport.findCart(BUYER_ID)).willReturn(Optional.of(cart()));

        assertThatThrownBy(() -> useCase.getLines(BUYER_ID, List.of(99L)))
                .extracting("resultCode").isEqualTo("404-CART_ITEM_NOT_FOUND");
        assertThat(useCase.getLines(BUYER_ID, List.of())).isEmpty();
        verifyNoInteractions(productApi);
    }
}
