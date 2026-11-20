package com.backend.boundedContext.product.out;

import java.util.Collection;

/**
 * 상품이 쓰는 쿠폰 기능
 */
public interface ProductCouponPort {

    /** 판매를 멈춘 상품의 상품 쿠폰을 모두 중지한다 (같은 트랜잭션) */
    void stopProductCoupons(Collection<Long> productIds, String reason);
}
