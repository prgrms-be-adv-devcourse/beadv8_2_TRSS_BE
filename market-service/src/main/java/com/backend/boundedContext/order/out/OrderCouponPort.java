package com.backend.boundedContext.order.out;

import java.time.LocalDateTime;

/**
 * 주문이 쓰는 쿠폰 기능 (예약, 사용, 해제). 오류 코드는 CouponApi 설명을 따른다
 */
public interface OrderCouponPort {

    /** 상품 쿠폰을 주문 항목에 예약하고 그 항목의 할인액을 돌려준다 */
    long reserveProductCoupon(Long buyerId, String code, Long orderId, Long orderItemId, Long productId, long lineAmount,
                              LocalDateTime now);

    /** 장바구니 쿠폰을 주문에 예약하고 할인액을 돌려준다. orderAmount는 상품 쿠폰 할인 후 상품 금액 합계 */
    long reserveCartCoupon(Long buyerId, Long couponId, Long orderId, long orderAmount, LocalDateTime now);

    /** 결제 성공: 예약된 쿠폰 사용 처리 (결제 요청 전에 부른다) */
    void useReservedCoupons(Long orderId, LocalDateTime now);

    /** 결제 전 취소, 만료: 예약된 쿠폰 해제 */
    void releaseReservedCoupons(Long orderId, LocalDateTime now);
}
