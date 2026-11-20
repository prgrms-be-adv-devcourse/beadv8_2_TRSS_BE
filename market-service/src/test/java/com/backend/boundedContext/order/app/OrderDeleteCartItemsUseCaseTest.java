package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Cart;
import com.backend.boundedContext.order.domain.CartItem;
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
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class OrderDeleteCartItemsUseCaseTest {

    @Mock
    OrderSupport orderSupport;

    @InjectMocks
    OrderDeleteCartItemsUseCase useCase; // 생성자에 위의 Mock이 들어간다

    @Test
    @DisplayName("요청한 항목만 삭제한다")
    void delete() {
        Cart cart = Cart.create(3L);
        CartItem item1 = cart.addItem(10L, 1, 99);
        CartItem item2 = cart.addItem(11L, 1, 99);
        ReflectionTestUtils.setField(item1, "id", 1L);
        ReflectionTestUtils.setField(item2, "id", 2L);
        given(orderSupport.findCart(3L)).willReturn(Optional.of(cart));

        useCase.delete(3L, List.of(1L));

        assertThat(cart.getItems()).containsExactly(item2);
    }

    @Test
    @DisplayName("장바구니가 없으면 아무 일도 하지 않는다")
    void delete_noCart() {
        given(orderSupport.findCart(3L)).willReturn(Optional.empty());

        assertThatNoException().isThrownBy(() -> useCase.delete(3L, List.of(1L)));
    }
}
