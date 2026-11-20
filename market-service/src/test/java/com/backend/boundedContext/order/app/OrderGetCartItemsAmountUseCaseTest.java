package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Cart;
import com.backend.boundedContext.order.domain.CartItem;
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
class OrderGetCartItemsAmountUseCaseTest {

    private static final Long BUYER_ID = 3L;

    @Mock OrderSupport orderSupport;
    @Mock ProductApi productApi;

    @InjectMocks OrderGetCartItemsAmountUseCase useCase;

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

    private OptionSnapshotDto option(Long optionId, long price, boolean saleable) {
        return new OptionSnapshotDto(optionId, optionId / 10, 2L, "상품" + optionId, "기본", price, price, null, 10,
                saleable, saleable ? null : ProductUnsaleableReason.PRODUCT_STOPPED, null);
    }

    @Test
    @DisplayName("고른 항목 중 판매 가능한 항목만 현재 가격 × 수량으로 더한다 (판매 불가, 없는 옵션은 0원)")
    void amount() {
        Cart cart = cartWith(100, 2, 200, 1, 300, 1, 400, 5);   // 항목 1~4
        given(orderSupport.findCart(BUYER_ID)).willReturn(Optional.of(cart));
        given(productApi.getOptions(List.of(100L, 200L, 300L))).willReturn(List.of(
                option(100L, 20_000, true),
                option(200L, 22_000, true),
                option(300L, 50_000, false)));   // 판매 중지

        long amount = useCase.getAmount(BUYER_ID, List.of(1L, 2L, 3L, 2L));   // 중복 ID는 한 번만

        assertThat(amount).isEqualTo(20_000 * 2 + 22_000);
    }

    @Test
    @DisplayName("내 장바구니 항목이 아니거나 장바구니가 없으면 404-CART_ITEM_NOT_FOUND")
    void notMine() {
        given(orderSupport.findCart(BUYER_ID)).willReturn(Optional.of(cartWith(100, 1)));

        assertThatThrownBy(() -> useCase.getAmount(BUYER_ID, List.of(99L)))
                .extracting("resultCode").isEqualTo("404-CART_ITEM_NOT_FOUND");

        given(orderSupport.findCart(BUYER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.getAmount(BUYER_ID, List.of(1L)))
                .extracting("resultCode").isEqualTo("404-CART_ITEM_NOT_FOUND");
    }

    @Test
    @DisplayName("항목을 고르지 않으면 조회 없이 0원이다")
    void empty() {
        assertThat(useCase.getAmount(BUYER_ID, List.of())).isZero();
        verifyNoInteractions(orderSupport, productApi);
    }
}
