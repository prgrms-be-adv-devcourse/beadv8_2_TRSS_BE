package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductStopRequestStatus;
import com.backend.boundedContext.product.out.ProductCouponPort;
import com.backend.boundedContext.product.out.ProductStopRequestRepository;
import com.backend.shared.order.dto.OrderCancelType;
import com.backend.shared.order.out.OrderApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ProductSaleEndSupportTest {

    @Mock ProductStopRequestRepository productStopRequestRepository;
    @Mock OrderApi orderApi;
    @Mock ProductCouponPort productCouponPort;

    @InjectMocks ProductSaleEndSupport productSaleEndSupport;

    @Test
    @DisplayName("판매를 멈춘 상품의 결제 대기 주문 취소와 함께 상품 쿠폰도 중지한다")
    void afterSaleEnded_stopsProductCoupons() {
        Product product = mock(Product.class);
        given(product.getId()).willReturn(10L);
        given(productStopRequestRepository.findAllByProductIdInAndStatus(List.of(10L), ProductStopRequestStatus.PENDING))
                .willReturn(List.of());

        productSaleEndSupport.afterSaleEnded(List.of(product));

        verify(orderApi).cancelPendingOrders(List.of(10L), OrderCancelType.PRODUCT_STOPPED);
        verify(productCouponPort).stopProductCoupons(List.of(10L), ProductSaleEndSupport.PRODUCT_COUPON_STOP_REASON);
    }

    @Test
    @DisplayName("상품이 없으면 아무것도 하지 않는다")
    void empty() {
        productSaleEndSupport.afterSaleEnded(List.of());

        verifyNoInteractions(productStopRequestRepository, orderApi, productCouponPort);
    }
}
