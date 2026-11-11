package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.CouponErrorCode;
import com.backend.boundedContext.coupon.domain.CouponType;
import com.backend.boundedContext.coupon.domain.CouponUsage;
import com.backend.boundedContext.coupon.out.CouponRepository;
import com.backend.boundedContext.coupon.out.CouponUsageRepository;
import com.backend.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 장바구니 쿠폰 예약
 *
 * <p>주문 생성 때 재고 차감과 같은 트랜잭션에서 부른다(OrderCreateOrderUseCase). 순서와 이유:
 * <ol>
 *     <li>쿠폰을 쓰기 잠금해 읽는다(수량 제한 여부와 상관없이). 관리자 중지도 같은 행을 잠그므로, 확인한 상태(운영 중·사용 기간)가
 *         이 트랜잭션이 끝날 때까지 바뀌지 않는다. 잠그지 않으면 확인 직후 중지가 커밋돼 예약은 되는데 결제 때 막히는 주문이 생긴다.
 *         같은 쿠폰의 예약은 차례로 처리된다.</li>
 *     <li>쿠폰 종류, 사용 기간, 1인 1회, 이 주문에 장바구니 쿠폰이 이미 있는지(주문당 1개), 최소 주문 금액을 본다.
 *         실패하면 수량을 건드리지 않는다.</li>
 *     <li>수량 제한이 있으면 조건부 UPDATE로 남은 수량을 1 줄인다. 바뀐 행이 없으면 소진이다.</li>
 *     <li>사용 기록을 바로 flush 한다. 1번 잠금으로 같은 쿠폰의 예약은 차례로 처리되므로 같은 회원의 두 번째 주문은 보통 2번 검사에서 막힌다.
 *         그래도 겹치면 부분 유니크 인덱스에 걸려 하나만 저장되고, 나머지는 409-COUPON_ALREADY_USED로 실패한다(마지막 안전장치).
 *         같은 주문에 서로 다른 장바구니 쿠폰을 동시에 예약하면(쿠폰 행이 달라 1번 잠금으로 차례가 정해지지 않음)
 *         uk_coupon_usage_order_type에 걸려 409-COUPON_ORDER_LIMIT_EXCEEDED로 실패한다.
 *         트랜잭션 전체가 롤백되므로 3번에서 줄인 수량도 함께 되돌아간다.</li>
 * </ol>
 * 반환값은 이 주문에 적용할 할인액이다.
 */
@Component
@RequiredArgsConstructor
public class CouponReserveCartCouponUseCase {

    private final CouponSupport couponSupport;
    private final CouponRepository couponRepository;
    private final CouponUsageRepository couponUsageRepository;

    /**
     * @param orderAmount 상품 쿠폰 할인 후 상품 금액 합계 (배송비 제외)
     */
    public long reserve(Long memberId, Long couponId, Long orderId, long orderAmount, LocalDateTime now) {
        Coupon coupon = couponSupport.getCouponForUpdate(couponId);

        if (!coupon.isCartCoupon()) {
            throw new DomainException(CouponErrorCode.COUPON_NOT_APPLICABLE);
        }
        if (!coupon.isUsableAt(now)) {
            throw new DomainException(CouponErrorCode.COUPON_NOT_ACTIVE);
        }
        if (couponUsageRepository.existsByCouponIdAndMemberIdAndStatusIn(couponId, memberId, CouponUsage.OCCUPIED_STATUSES)) {
            throw new DomainException(CouponErrorCode.COUPON_ALREADY_USED);
        }
        if (couponUsageRepository.existsByOrderIdAndCouponTypeAndStatusIn(orderId, CouponType.CART,
                CouponUsage.OCCUPIED_STATUSES)) {
            throw new DomainException(CouponErrorCode.COUPON_ORDER_LIMIT_EXCEEDED);
        }

        long discountAmount = coupon.cartDiscountFor(orderAmount);
        if (discountAmount == 0) {
            throw new DomainException(CouponErrorCode.COUPON_MIN_ORDER_NOT_MET);
        }

        if (coupon.isQuantityLimited() && couponRepository.decreaseRemainingQuantity(couponId) == 0) {
            throw new DomainException(CouponErrorCode.COUPON_SOLD_OUT);
        }

        try {
            couponUsageRepository.saveAndFlush(CouponUsage.reserve(coupon, memberId, orderId, null, discountAmount, now));
        } catch (DataIntegrityViolationException e) {
            // 2번 검사 뒤 다른 요청이 먼저 예약을 커밋한 경우. 어느 인덱스에 걸렸는지로 나눈다
            throw new DomainException(CouponUsageConstraints.errorCodeOf(e), e);
        }

        return discountAmount;
    }
}
