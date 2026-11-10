package com.backend.boundedContext.order.out;

import com.backend.shared.coupon.out.CouponApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * {@link OrderCouponPort} 구현. 같은 앱(market-service)의 쿠폰 컨텍스트를 {@link CouponApi}로 부른다
 */
@Component
@RequiredArgsConstructor
public class OrderCouponApiAdapter implements OrderCouponPort {

    private final CouponApi couponApi;

    @Override
    public long reserveProductCoupon(Long buyerId, String code, Long orderId, Long orderItemId, Long productId,
                                     long lineAmount, LocalDateTime now) {
        return couponApi.reserveProductCoupon(buyerId, code, orderId, orderItemId, productId, lineAmount, now);
    }

    @Override
    public long reserveCartCoupon(Long buyerId, Long couponId, Long orderId, long orderAmount, LocalDateTime now) {
        return couponApi.reserveCartCoupon(buyerId, couponId, orderId, orderAmount, now);
    }

    @Override
    public void useReservedCoupons(Long orderId, LocalDateTime now) {
        couponApi.useReservedCoupons(orderId, now);
    }

    @Override
    public void releaseReservedCoupons(Long orderId, LocalDateTime now) {
        couponApi.releaseReservedCoupons(orderId, now);
    }
}
