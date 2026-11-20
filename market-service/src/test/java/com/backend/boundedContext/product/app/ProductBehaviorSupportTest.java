package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.ProductBehaviorPolicy;
import com.backend.boundedContext.product.out.wishlist.WishlistRepository;
import com.backend.shared.order.out.OrderApi;
import com.backend.shared.review.out.ReviewApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** 판매량이 바뀌었을 수 있는 상품: 구간 안에서 결제·취소·반품된 상품 + 구간 동안 최근 30일 밖으로 나간 결제의 상품 */
class ProductBehaviorSupportTest {

    private final OrderApi orderApi = mock(OrderApi.class);
    private final ProductBehaviorSupport support = new ProductBehaviorSupport(mock(WishlistRepository.class), mock(ReviewApi.class),
            orderApi, new ProductBehaviorPolicy(0.4, 0.4, 0.2, 1_000, 500, 4.0, 5, 30, 60, 30, 1440));

    @Test
    @DisplayName("상태가 바뀐 주문의 상품과 (from−30일, to−30일]에 결제된 상품을 합치고 중복을 뺀다")
    void mergesStatusChangesAndExpiredSales() {
        LocalDateTime from = LocalDateTime.of(2026, 11, 18, 12, 0);
        LocalDateTime to = from.plusMinutes(1);
        given(orderApi.findProductIdsWithSalesChanged(from, to)).willReturn(List.of(1L, 2L));
        given(orderApi.findProductIdsPaidBetween(from.minusDays(30), to.minusDays(30))).willReturn(List.of(2L, 3L));

        assertThat(support.findProductIdsWithSalesChanged(from, to)).containsExactlyInAnyOrder(1L, 2L, 3L);
    }
}
