package com.backend.boundedContext.coupon;

import com.backend.boundedContext.coupon.domain.CartCouponTierSpec;
import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.DiscountType;
import com.backend.boundedContext.coupon.domain.Partnership;
import com.backend.boundedContext.coupon.domain.ProductCoupon;
import com.backend.boundedContext.coupon.domain.ProductCouponDiscount;
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

    public static final long SELLER_ID = 20L;
    public static final long INFLUENCER_ID = 30L;
    public static final long PRODUCT_ID = 40L;

    /** 정액 3,000원 할인 */
    public static final ProductCouponDiscount FIXED_3000 = new ProductCouponDiscount(DiscountType.FIXED, 3_000, null);

    /** 유효 기간을 정해 만든 상품 쿠폰(SELLER_ID 발행). 등록 시각은 시작 1초 전 */
    public static Coupon productCouponBase(LocalDateTime validFrom, LocalDateTime validUntil, Integer totalQuantity) {
        return Coupon.createProductCoupon(SELLER_ID, "인플루언서 쿠폰", totalQuantity != null, totalQuantity,
                validFrom, validUntil, validFrom.minusSeconds(1));
    }

    /** 진행 중 협약(SELLER_ID와 INFLUENCER_ID) */
    public static Partnership activePartnership() {
        Partnership partnership = Partnership.request(SELLER_ID, INFLUENCER_ID, 20, "함께해요", LocalDateTime.now().minusDays(2));
        partnership.accept(LocalDateTime.now().minusDays(1));
        return partnership;
    }

    /** 단위 테스트용으로 협약에 ID를 넣는다 */
    public static Partnership withId(Partnership partnership, Long id) {
        ReflectionTestUtils.setField(partnership, "id", id);
        return partnership;
    }

    /** 상품 쿠폰 정보 (PRODUCT_ID 대상) */
    public static ProductCoupon productCoupon(Coupon coupon, Long partnershipId, String code, ProductCouponDiscount discount) {
        return ProductCoupon.create(coupon, partnershipId, INFLUENCER_ID, PRODUCT_ID, code, discount);
    }
}
