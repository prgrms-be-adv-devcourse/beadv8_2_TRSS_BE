package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.out.CartRepository;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.dto.ProductUnsaleableReason;
import com.backend.shared.product.out.ProductApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class OrderSupportTest {

    @Mock CartRepository cartRepository;
    @Mock OrderRepository orderRepository;
    @Mock ProductApi productApi;
    @InjectMocks
    OrderSupport orderSupport;

    private OptionSnapshotDto option(Long optionId, boolean saleable) {
        return new OptionSnapshotDto(optionId, optionId / 10, 2L, "상품" + optionId, "기본", 15000, 10,
                saleable, saleable ? null : ProductUnsaleableReason.PRODUCT_STOPPED, null);
    }

    @Test
    @DisplayName("getSaleableOptions: 모두 판매 가능하면 옵션 ID로 찾을 수 있게 돌려준다")
    void saleableOptions() {
        given(productApi.getOptions(List.of(100L, 110L))).willReturn(List.of(option(110L, true), option(100L, true)));

        assertThat(orderSupport.getSaleableOptions(List.of(100L, 110L))).containsOnlyKeys(100L, 110L);
    }

    @Test
    @DisplayName("getSaleableOptions: 옵션이 없으면 PRODUCT_NOT_FOUND")
    void optionNotFound() {
        given(productApi.getOptions(List.of(100L))).willReturn(List.of());

        assertThatThrownBy(() -> orderSupport.getSaleableOptions(List.of(100L)))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "404-PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("getSaleableOptions: 판매 불가 옵션이면 PRODUCT_NOT_ON_SALE")
    void notOnSale() {
        given(productApi.getOptions(List.of(100L))).willReturn(List.of(option(100L, false)));

        assertThatThrownBy(() -> orderSupport.getSaleableOptions(List.of(100L)))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("resultCode", "409-PRODUCT_NOT_ON_SALE");
    }
}
