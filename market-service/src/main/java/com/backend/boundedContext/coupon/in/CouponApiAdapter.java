package com.backend.boundedContext.coupon.in;

import com.backend.boundedContext.coupon.app.CouponFacade;
import com.backend.shared.coupon.out.CouponApi;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
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

    @Override
    public long reserveProductCoupon(Long memberId, String code, Long orderId, Long orderItemId, Long productId,
                                     long lineAmount, LocalDateTime now) {
        return couponFacade.reserveProductCoupon(memberId, code, orderId, orderItemId, productId, lineAmount, now);
    }

    @Override
    public long reserveCartCoupon(Long memberId, Long couponId, Long orderId, long orderAmount, LocalDateTime now) {
        return couponFacade.reserveCartCoupon(memberId, couponId, orderId, orderAmount, now);
    }

    @Override
    public void validateReservedCouponsUsable(Long orderId, LocalDateTime now) {
        couponFacade.validateReservedCouponsUsable(orderId, now);
    }

    @Override
    public int useReservedCoupons(Long orderId, LocalDateTime now) {
        return couponFacade.useReservedCoupons(orderId, now);
    }

    @Override
    public int releaseReservedCoupons(Long orderId, LocalDateTime now) {
        return couponFacade.releaseReservedCoupons(orderId, now);
    }
}
