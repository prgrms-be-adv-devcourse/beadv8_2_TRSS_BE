package com.backend.boundedContext.order.domain;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 주문 쿠폰 정책
 *
 * <p>쿠폰은 설정(custom.order.couponEnabled)이 켜져 있을 때만 주문에 반영한다. 기본값은 꺼짐이다.
 * 결제 서비스(장바구니 쿠폰 지원금 COUPON_SUBSIDY)와 정산(상품 쿠폰 할인 후 금액)이 아직 쿠폰을 모르기 때문이다.
 * 켜면 결제 금액이 상품 금액 합계와 달라지므로, 결제와 정산 작업이 끝난 뒤에 켠다.
 */
@Component
@Getter
public class OrderCouponPolicy {

    // 쿠폰을 주문에 반영하는지
    private final boolean couponEnabled;

    public OrderCouponPolicy(@Value("${custom.order.couponEnabled:false}") boolean couponEnabled) {
        this.couponEnabled = couponEnabled;
    }
}
