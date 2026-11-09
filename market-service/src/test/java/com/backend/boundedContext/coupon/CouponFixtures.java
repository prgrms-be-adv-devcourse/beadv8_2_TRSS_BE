package com.backend.boundedContext.coupon;

import com.backend.boundedContext.coupon.domain.CartCouponTierSpec;
import com.backend.boundedContext.coupon.domain.Coupon;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 쿠폰 테스트용 데이터
 */
public final class CouponFixtures {

    public static final long ADMIN_ID = 1L;

    // 3만 원 이상 3천 원, 5만 원 이상 5천 원, 7만 원 이상 7천 원
    public static final List<CartCouponTierSpec> TIERS = List.of(
            new CartCouponTierSpec(30_000, 3_000),
            new CartCouponTierSpec(50_000, 5_000),
            new CartCouponTierSpec(70_000, 7_000));

    private CouponFixtures() {
    }

    /**
     * 유효 기간을 정해 만든 장바구니 쿠폰. 등록 시각은 시작 1초 전으로 둬서 지난 기간의 쿠폰도 만들 수 있게 한다.
     * totalQuantity가 null이면 수량 제한 없음
     */
    public static Coupon cartCoupon(String name, LocalDateTime validFrom, LocalDateTime validUntil, Integer totalQuantity) {
        return Coupon.createCartCoupon(ADMIN_ID, name, TIERS, totalQuantity != null, totalQuantity,
                validFrom, validUntil, validFrom.minusSeconds(1));
    }

    /** 어제부터 내일까지 사용 가능한 장바구니 쿠폰 */
    public static Coupon activeCartCoupon(Integer totalQuantity) {
        LocalDateTime now = LocalDateTime.now();
        return cartCoupon("장바구니 쿠폰", now.minusDays(1), now.plusDays(1), totalQuantity);
    }

    /** 단위 테스트용으로 ID를 넣는다 */
    public static Coupon withId(Coupon coupon, Long id) {
        ReflectionTestUtils.setField(coupon, "id", id);
        return coupon;
    }
}
