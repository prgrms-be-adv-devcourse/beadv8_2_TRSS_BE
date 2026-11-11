package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Cart;
import com.backend.boundedContext.order.domain.CartItem;
import com.backend.boundedContext.order.domain.OrderPolicy;
import com.backend.boundedContext.order.in.dto.CartResponse;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.out.ProductApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * [QA] TRSS-210 장바구니 배송비: 선택 해제 항목은 기준에서 빠지고, 설정값(OrderPolicy)을 그대로 따른다.
 */
@ExtendWith(MockitoExtension.class)
class OrderGetCartShippingFeeQaTest {

    private static final Long BUYER_ID = 3L;

    @Mock OrderSupport orderSupport;
    @Mock ProductApi productApi;
    @Mock MemberApi memberApi;

    private OrderGetCartUseCase useCase(long shippingFee, long threshold) {
        return new OrderGetCartUseCase(orderSupport, productApi, memberApi,
                new OrderPolicy(15, 7, 99, 100, 1, shippingFee, threshold));
    }

    private OptionSnapshotDto onSale(Long optionId, long price) {
        return new OptionSnapshotDto(optionId, optionId / 10, 2L, "상품" + optionId, "기본", price, 10, true, null, null);
    }

    @Test
    @DisplayName("선택 해제한 항목은 배송비 기준에서 빠진다: 선택 20,000원 + 미선택 20,000원 → 배송비 2,500원, 남은 금액 10,000원")
    void unselectedItemsExcluded() {
        Cart cart = Cart.create(BUYER_ID);
        CartItem selected = cart.addItem(100L, 1, 99);
        CartItem unselected = cart.addItem(110L, 1, 99);
        ReflectionTestUtils.setField(selected, "id", 1L);
        ReflectionTestUtils.setField(unselected, "id", 2L);
        unselected.changeSelected(false);
        given(orderSupport.findCart(BUYER_ID)).willReturn(Optional.of(cart));
        given(productApi.getOptions(List.of(100L, 110L))).willReturn(List.of(onSale(100L, 20_000), onSale(110L, 20_000)));

        CartResponse response = useCase(2_500, 30_000).getCart(BUYER_ID);

        assertThat(response.selectedCount()).isEqualTo(1);
        assertThat(response.orderable()).isTrue();
        assertThat(response.expectedTotalPrice()).isEqualTo(20_000);
        assertThat(response.shippingFee()).isEqualTo(2_500);
        assertThat(response.expectedPayAmount()).isEqualTo(22_500);
        assertThat(response.remainingForFreeShipping()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("기준값은 설정(custom.order.*)을 따른다: 배송비 3,000원·기준 50,000원이면 40,000원 장바구니는 3,000원, 남은 10,000원")
    void followsConfiguredPolicy() {
        Cart cart = Cart.create(BUYER_ID);
        ReflectionTestUtils.setField(cart.addItem(100L, 2, 99), "id", 1L);
        given(orderSupport.findCart(BUYER_ID)).willReturn(Optional.of(cart));
        given(productApi.getOptions(List.of(100L))).willReturn(List.of(onSale(100L, 20_000)));

        CartResponse response = useCase(3_000, 50_000).getCart(BUYER_ID);

        assertThat(response.expectedTotalPrice()).isEqualTo(40_000);
        assertThat(response.shippingFee()).isEqualTo(3_000);
        assertThat(response.expectedPayAmount()).isEqualTo(43_000);
        assertThat(response.remainingForFreeShipping()).isEqualTo(10_000);
    }
}
