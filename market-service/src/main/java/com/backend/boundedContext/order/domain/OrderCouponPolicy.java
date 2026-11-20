package com.backend.boundedContext.order.domain;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 주문 쿠폰 정책
 *
 * <p>쿠폰은 설정(custom.order.couponEnabled)이 켜져 있을 때만 주문에 반영한다. 기본값은 켜짐이다.
 * 결제(장바구니 쿠폰 지원금 COUPON_SUBSIDY), 정산(상품 쿠폰 할인 후 금액), 반품 환불(지원금 회수 COUPON_SUBSIDY_CANCEL)이
 * 모두 쿠폰을 지원한다. 문제가 생기면 환경변수 ORDER_COUPON_ENABLED=false로 끄면 쿠폰을 보낸 주문은
 * 409-ORDER_COUPON_NOT_AVAILABLE이 되고 결제 금액은 상품 금액 합계와 같아진다.
 */
@Component
@Getter
public class OrderCouponPolicy {

    // 쿠폰을 주문에 반영하는지
    private final boolean couponEnabled;

    public OrderCouponPolicy(@Value("${custom.order.couponEnabled:true}") boolean couponEnabled) {
        this.couponEnabled = couponEnabled;
    }
}
