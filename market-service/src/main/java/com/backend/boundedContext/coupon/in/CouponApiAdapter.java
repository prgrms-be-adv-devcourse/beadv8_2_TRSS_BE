package com.backend.boundedContext.coupon.in;

import com.backend.boundedContext.coupon.app.CouponFacade;
import com.backend.shared.coupon.out.CouponApi;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * {@link CouponApi} 구현체. 규칙은 {@link CouponApi}의 설명을 따른다.
 */
@Component
@RequiredArgsConstructor
public class CouponApiAdapter implements CouponApi {

    @Lazy  // 컨텍스트 간 순환 참조 방지 (상품 → 쿠폰 → 상품)
    private final CouponFacade couponFacade;

    @Override
    public int stopProductCouponsByProducts(Collection<Long> productIds, String reason) {
        return couponFacade.stopProductCouponsByProducts(productIds, reason);
    }
}
