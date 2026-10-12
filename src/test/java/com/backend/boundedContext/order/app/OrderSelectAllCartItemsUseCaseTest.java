package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Cart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderSelectAllCartItemsUseCaseTest {

    @Mock
    OrderSupport orderSupport;

    @InjectMocks
    OrderSelectAllCartItemsUseCase useCase;

    @Test
    @DisplayName("전체 해제하면 모든 항목이 선택 해제된다")
    void selectAll_false() {
        Cart cart = Cart.create(3L);
        cart.addItem(10L, 1, 99);
        cart.addItem(11L, 1, 99);
        given(orderSupport.findCart(3L)).willReturn(Optional.of(cart));

        useCase.selectAll(3L, false);

        assertThat(cart.getItems()).allMatch(item -> !item.isSelected());
        verify(orderSupport).findCart(3L); // 장바구니를 조회했는지 확인
    }
}
