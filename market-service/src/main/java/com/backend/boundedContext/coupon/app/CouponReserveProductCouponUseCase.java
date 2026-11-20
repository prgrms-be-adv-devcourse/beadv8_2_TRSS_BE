package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.CouponErrorCode;
import com.backend.boundedContext.coupon.domain.CouponType;
import com.backend.boundedContext.coupon.domain.CouponUsage;
import com.backend.boundedContext.coupon.domain.ProductCoupon;
import com.backend.boundedContext.coupon.out.CouponRepository;
import com.backend.boundedContext.coupon.out.CouponUsageRepository;
import com.backend.boundedContext.coupon.out.ProductCouponRepository;
import com.backend.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 상품 쿠폰 예약 (주문 생성 때 재고 차감과 같은 트랜잭션)
 *
 * <p>순서는 장바구니 쿠폰 예약과 같다.
 * <ol>
 *     <li>코드로 쿠폰 ID만 찾고(404-COUPON_CODE_NOT_FOUND) 쿠폰을 쓰기 잠금해 읽는다. 잠근 뒤 처음 읽어야
 *         판매자 중지, 관리자 중지, 자동 중지가 이미 커밋한 최신 상태를 본다.</li>
 *     <li>대상 상품이 이 주문 항목의 상품인지(400-COUPON_NOT_APPLICABLE), 사용 기간 안이고 중지되지 않았는지(409-COUPON_NOT_ACTIVE),
 *         1인 1회(409-COUPON_ALREADY_USED), 이 주문에 상품 쿠폰이 이미 있는지(409-COUPON_ORDER_LIMIT_EXCEEDED)를 본다.</li>
 *     <li>수량 제한이 있으면 조건부 UPDATE로 남은 수량을 1 줄인다(409-COUPON_SOLD_OUT).</li>
 *     <li>사용 기록(주문 항목 포함)을 바로 flush 한다. 동시 요청은 부분 유니크 인덱스가 마지막으로 막는다.</li>
 * </ol>
 * 반환값은 이 항목에 적용할 할인액이고, 항목 금액(단가 x 수량)을 넘지 않는다.
 */
@Component
@RequiredArgsConstructor
public class CouponReserveProductCouponUseCase {

    private final CouponSupport couponSupport;
    private final CouponRepository couponRepository;
    private final CouponUsageRepository couponUsageRepository;
    private final ProductCouponRepository productCouponRepository;

    /**
     * @param productId  할인을 붙일 주문 항목의 상품
     * @param lineAmount 그 주문 항목 금액 (단가 x 수량)
     */
    public long reserve(Long memberId, String code, Long orderId, Long orderItemId, Long productId, long lineAmount,
                        LocalDateTime now) {
        Long couponId = productCouponRepository.findCouponIdByCode(CouponCodeGenerator.normalize(code))
                .orElseThrow(() -> new DomainException(CouponErrorCode.COUPON_CODE_NOT_FOUND));
        Coupon coupon = couponSupport.getCouponForUpdate(couponId);
        ProductCoupon productCoupon = productCouponRepository.findWithCouponByCouponId(couponId)
                .orElseThrow(() -> new DomainException(CouponErrorCode.COUPON_CODE_NOT_FOUND));

        if (!productCoupon.getProductId().equals(productId)) {
            throw new DomainException(CouponErrorCode.COUPON_NOT_APPLICABLE);
        }
        if (!coupon.isUsableAt(now)) {
            throw new DomainException(CouponErrorCode.COUPON_NOT_ACTIVE);
        }
        if (couponUsageRepository.existsByCouponIdAndMemberIdAndStatusIn(couponId, memberId, CouponUsage.OCCUPIED_STATUSES)) {
            throw new DomainException(CouponErrorCode.COUPON_ALREADY_USED);
        }
        if (couponUsageRepository.existsByOrderIdAndCouponTypeAndStatusIn(orderId, CouponType.PRODUCT,
                CouponUsage.OCCUPIED_STATUSES)) {
            throw new DomainException(CouponErrorCode.COUPON_ORDER_LIMIT_EXCEEDED);
        }

        long discountAmount = productCoupon.discountFor(lineAmount);
        if (discountAmount == 0) {
            throw new DomainException(CouponErrorCode.COUPON_NOT_APPLICABLE);
        }

        if (coupon.isQuantityLimited() && couponRepository.decreaseRemainingQuantity(couponId) == 0) {
            throw new DomainException(CouponErrorCode.COUPON_SOLD_OUT);
        }

        try {
            couponUsageRepository.saveAndFlush(
                    CouponUsage.reserve(coupon, memberId, orderId, orderItemId, discountAmount, now));
        } catch (DataIntegrityViolationException e) {
            // 2번 검사 뒤 다른 요청이 먼저 예약을 커밋한 경우. 어느 인덱스에 걸렸는지로 나눈다
            throw new DomainException(CouponUsageConstraints.errorCodeOf(e), e);
        }

        return discountAmount;
    }
}
