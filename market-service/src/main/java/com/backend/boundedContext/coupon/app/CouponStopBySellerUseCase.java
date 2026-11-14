package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.CouponErrorCode;
import com.backend.boundedContext.coupon.domain.CouponStoppedBy;
import com.backend.boundedContext.coupon.out.CouponRepository;
import com.backend.boundedContext.coupon.out.CouponSellerPort;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 판매자 쿠폰 중지 → STOPPED. 이미 주문에 예약된 쿠폰은 그대로 결제와 취소를 따라간다.
 * 없는 쿠폰은 404-COUPON_NOT_FOUND, 내가 발행한 쿠폰이 아니면 403-FORBIDDEN,
 * 이미 중지됐거나 끝난 쿠폰은 409-COUPON_STATE_INVALID.
 */
@Component
@RequiredArgsConstructor
public class CouponStopBySellerUseCase {

    private final CouponSellerPort couponSellerPort;
    private final CouponRepository couponRepository;

    public void stop(Long memberId, Long couponId) {
        Long sellerId = couponSellerPort.getApprovedSellerId(memberId);
        Coupon coupon = couponRepository.findByIdForUpdate(couponId)
                .orElseThrow(() -> new DomainException(CouponErrorCode.COUPON_NOT_FOUND));
        if (!coupon.isIssuedBySeller(sellerId)) {
            throw new DomainException(GlobalErrorCode.FORBIDDEN);
        }

        coupon.stop(CouponStoppedBy.SELLER, null, LocalDateTime.now());
    }
}
