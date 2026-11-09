package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.CouponErrorCode;
import com.backend.boundedContext.coupon.domain.CouponStoppedBy;
import com.backend.boundedContext.coupon.out.CouponRepository;
import com.backend.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 관리자 쿠폰 강제 중지 (사유 필수)
 * 쿠폰을 잠근 뒤 판단하므로 같은 요청이 겹쳐도 한 번만 중지된다.
 * 없는 쿠폰은 404-COUPON_NOT_FOUND, 이미 중지됐거나 끝난 쿠폰은 409-COUPON_STATE_INVALID.
 * 이미 주문에 예약된 쿠폰은 그대로 결제와 취소를 따라간다.
 */
@Component
@RequiredArgsConstructor
public class CouponStopByAdminUseCase {

    private final CouponRepository couponRepository;

    public void stop(Long couponId, String reason) {
        Coupon coupon = couponRepository.findByIdForUpdate(couponId)
                .orElseThrow(() -> new DomainException(CouponErrorCode.COUPON_NOT_FOUND));

        coupon.stop(CouponStoppedBy.ADMIN, reason, LocalDateTime.now());
    }
}
