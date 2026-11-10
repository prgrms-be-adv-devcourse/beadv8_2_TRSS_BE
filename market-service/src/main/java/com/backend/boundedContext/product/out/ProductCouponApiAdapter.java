package com.backend.boundedContext.product.out;

import com.backend.shared.coupon.out.CouponApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * {@link ProductCouponPort} 구현. 같은 앱(market-service)의 쿠폰 컨텍스트를 {@link CouponApi}로 부른다
 */
@Component
@RequiredArgsConstructor
public class ProductCouponApiAdapter implements ProductCouponPort {

    private final CouponApi couponApi;

    @Override
    public void stopProductCoupons(Collection<Long> productIds, String reason) {
        couponApi.stopProductCouponsByProducts(productIds, reason);
    }
}
