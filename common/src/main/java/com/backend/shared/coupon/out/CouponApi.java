package com.backend.shared.coupon.out;

import java.util.Collection;

/**
 * 쿠폰 컨텍스트가 다른 컨텍스트에 제공하는 내부 API
 */
public interface CouponApi {

    /**
     * 판매를 멈춘(판매 중지, 삭제) 상품의 상품 쿠폰을 모두 중지한다 (중지 주체 SYSTEM).
     * 상품 컨텍스트가 상품 상태를 바꾼 트랜잭션 안에서 부른다.
     *
     * <ul>
     *   <li>아직 끝나지 않은 운영 중 쿠폰만 중지한다. 이미 중지됐거나 기간이 끝난 쿠폰은 그대로 둔다.</li>
     *   <li>이미 주문에 예약된 쿠폰은 그대로 결제와 취소를 따라간다(결제 때 중지 여부를 다시 본다).</li>
     *   <li>중지된 쿠폰은 상품이 다시 판매 중이 돼도 되살리지 않는다. 다시 호출해도 결과가 같다.</li>
     * </ul>
     *
     * @return 중지한 쿠폰 수
     */
    int stopProductCouponsByProducts(Collection<Long> productIds, String reason);
}
