package com.backend.shared.coupon.out;

import java.time.LocalDateTime;
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

    /**
     * 상품 쿠폰을 주문 항목에 예약한다 (주문 생성 트랜잭션 안에서, 재고 차감 뒤). 반환값은 그 항목의 할인액이다.
     * 코드가 없으면 404-COUPON_CODE_NOT_FOUND, 대상 상품이 아니면 400-COUPON_NOT_APPLICABLE,
     * 사용 기간 밖이거나 중지됐으면 409-COUPON_NOT_ACTIVE, 소진이면 409-COUPON_SOLD_OUT,
     * 이미 썼으면 409-COUPON_ALREADY_USED, 이 주문에 상품 쿠폰이 이미 있으면 409-COUPON_ORDER_LIMIT_EXCEEDED.
     *
     * @param lineAmount 그 주문 항목 금액 (단가 x 수량)
     */
    long reserveProductCoupon(Long memberId, String code, Long orderId, Long orderItemId, Long productId, long lineAmount,
                              LocalDateTime now);

    /**
     * 장바구니 쿠폰을 주문에 예약한다 (주문 생성 트랜잭션 안에서, 상품 쿠폰 다음). 반환값은 할인액이다.
     * 최소 주문 금액에 못 미치면 400-COUPON_MIN_ORDER_NOT_MET. 나머지 오류는 상품 쿠폰과 같다.
     *
     * @param orderAmount 상품 쿠폰 할인 후 상품 금액 합계 (배송비 제외)
     */
    long reserveCartCoupon(Long memberId, Long couponId, Long orderId, long orderAmount, LocalDateTime now);

    /**
     * 결제 성공: 주문에 예약된 쿠폰을 모두 사용 처리한다 (결제 트랜잭션 안에서, 결제 요청 전).
     * 예약 뒤 중지됐거나 기간이 끝난 쿠폰이 있으면 409-COUPON_NOT_ACTIVE로 결제 전체를 롤백시킨다. 예약이 없으면 0.
     */
    int useReservedCoupons(Long orderId, LocalDateTime now);

    /**
     * 결제 전 취소, 만료: 주문에 예약된 쿠폰을 모두 해제하고 수량 제한 쿠폰의 남은 수량을 되돌린다. 예약이 없으면 0.
     * 다시 불러도 결과가 같다.
     */
    int releaseReservedCoupons(Long orderId, LocalDateTime now);
}
