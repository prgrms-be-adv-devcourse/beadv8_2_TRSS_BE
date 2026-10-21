package com.backend.boundedContext.order.domain;

import com.backend.global.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cart 도메인 단위 테스트. 의존성이 없으므로 Mock 없이 진짜 객체로 테스트한다.
 */
class CartTest {

    private static final int MAX = 99;

    @Test
    @DisplayName("새 옵션을 담으면 선택된 상태의 항목이 추가된다")
    void addItem_new() {
        Cart cart = Cart.create(3L);

        cart.addItem(10L, 2, MAX);

        assertThat(cart.getItems()).hasSize(1);
        CartItem item = cart.getItems().get(0);
        assertThat(item.getOptionId()).isEqualTo(10L);
        assertThat(item.getQuantity()).isEqualTo(2);
        assertThat(item.isSelected()).isTrue();
    }

    @Test
    @DisplayName("같은 옵션을 다시 담으면 수량이 합산된다")
    void addItem_merge() {
        Cart cart = Cart.create(3L);

        cart.addItem(10L, 2, MAX);
        cart.addItem(10L, 3, MAX);

        assertThat(cart.getItems()).hasSize(1);
        assertThat(cart.getItems().get(0).getQuantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("합산 수량이 최대 수량을 넘으면 CART_QUANTITY_INVALID")
    void addItem_exceedMax() {
        Cart cart = Cart.create(3L);
        cart.addItem(10L, 80, MAX);

        assertThatThrownBy(() -> cart.addItem(10L, 30, MAX))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "400-CART_QUANTITY_INVALID");
        assertThat(cart.getItems().get(0).getQuantity()).isEqualTo(80); // 기존 수량 유지
    }

    @Test
    @DisplayName("수량이 0 이하면 CART_QUANTITY_INVALID")
    void addItem_zero() {
        Cart cart = Cart.create(3L);

        assertThatThrownBy(() -> cart.addItem(10L, 0, MAX))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "400-CART_QUANTITY_INVALID");
    }

    @Test
    @DisplayName("내 장바구니에 없는 항목을 찾으면 CART_ITEM_NOT_FOUND")
    void getItem_notFound() {
        Cart cart = Cart.create(3L);
        CartItem item = cart.addItem(10L, 1, MAX);
        setId(item, 1L);

        assertThat(cart.getItem(1L)).isSameAs(item);
        assertThatThrownBy(() -> cart.getItem(999L))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "404-CART_ITEM_NOT_FOUND");
    }

    @Test
    @DisplayName("전체 해제하면 모든 항목의 selected가 false가 된다")
    void selectAll() {
        Cart cart = Cart.create(3L);
        cart.addItem(10L, 1, MAX);
        cart.addItem(11L, 1, MAX);

        cart.selectAll(false);

        assertThat(cart.getItems()).allMatch(item -> !item.isSelected());
    }

    @Test
    @DisplayName("요청한 ID의 항목만 삭제하고, 없는 ID는 무시한다")
    void removeItems() {
        Cart cart = Cart.create(3L);
        setId(cart.addItem(10L, 1, MAX), 1L);
        setId(cart.addItem(11L, 1, MAX), 2L);

        cart.removeItems(List.of(1L, 999L));

        assertThat(cart.getItems()).extracting(CartItem::getOptionId).containsExactly(11L);
    }

    /** IDENTITY ID는 DB에 저장해야 생기므로, 단위 테스트에서는 직접 넣어 준다 */
    private void setId(CartItem item, Long id) {
        ReflectionTestUtils.setField(item, "id", id);
    }
}
