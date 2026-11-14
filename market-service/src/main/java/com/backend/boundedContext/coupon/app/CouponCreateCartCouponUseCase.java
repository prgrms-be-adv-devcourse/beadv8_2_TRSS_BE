package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.app.dto.CartCouponCreateCommand;
import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.out.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 장바구니 쿠폰 등록 (관리자)
 * 유효 기간이 틀리면 400-COUPON_PERIOD_INVALID, 구간이 틀리면 400-COUPON_TIER_INVALID.
 */
@Component
@RequiredArgsConstructor
public class CouponCreateCartCouponUseCase {

    private final CouponRepository couponRepository;

    public Long create(Long adminId, CartCouponCreateCommand command) {
        Coupon coupon = Coupon.createCartCoupon(
                adminId,
                command.name(),
                command.tiers(),
                command.quantityLimited(),
                command.totalQuantity(),
                command.validFrom(),
                command.validUntil(),
                LocalDateTime.now()
        );

        return couponRepository.save(coupon).getId();
    }
}
