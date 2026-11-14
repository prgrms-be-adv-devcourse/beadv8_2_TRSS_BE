package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.CouponFixtures;
import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.CouponUsageStatus;
import com.backend.boundedContext.coupon.domain.DiscountType;
import com.backend.boundedContext.coupon.domain.Partnership;
import com.backend.boundedContext.coupon.domain.ProductCoupon;
import com.backend.boundedContext.coupon.domain.ProductCouponDiscount;
import com.backend.boundedContext.coupon.out.CouponInfluencerPort;
import com.backend.boundedContext.coupon.out.CouponProductPort;
import com.backend.boundedContext.coupon.out.CouponRepository;
import com.backend.boundedContext.coupon.out.CouponUsageRepository;
import com.backend.boundedContext.coupon.out.InfluencerPickPort;
import com.backend.boundedContext.coupon.out.PartnershipRepository;
import com.backend.boundedContext.coupon.out.ProductCouponRepository;
import com.backend.shared.coupon.out.CouponApi;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.out.OrderApi;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 주문이 쓰는 쿠폰 API(CouponApi)를 실제 DB로 확인한다: 상품 쿠폰과 장바구니 쿠폰 예약, 결제 때 사용 처리, 취소 때 해제.
 * 테스트마다 롤백된다. 다른 컨텍스트 포트는 mock으로 대신한다(ProductCouponIntegrationTest와 같은 조합).
 */
@SpringBootTest
@Transactional
class OrderCouponReservationIntegrationTest {

    private static final long MEMBER_ID = 9_400_001L;
    private static final long SELLER_ID = 9_400_002L;
    private static final long INFLUENCER_ID = 9_400_003L;
    private static final long PRODUCT_ID = 9_400_004L;
    private static final long ORDER_ID = 9_400_100L;
    private static final long ORDER_ITEM_ID = 9_400_101L;
    private static final String CODE = "ORDCPN2345";

    @Autowired private CouponApi couponApi;
    @Autowired private CouponRepository couponRepository;
    @Autowired private CouponUsageRepository couponUsageRepository;
    @Autowired private PartnershipRepository partnershipRepository;
    @Autowired private ProductCouponRepository productCouponRepository;
    @Autowired private EntityManager entityManager;

    @MockitoBean private MemberApi memberApi;
    @MockitoBean private OrderApi orderApi;
    @MockitoBean private CouponInfluencerPort couponInfluencerPort;
    @MockitoBean private InfluencerPickPort influencerPickPort;
    @MockitoBean private CouponProductPort couponProductPort;

    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    }

    /** 진행 중 협약과 그 협약의 상품 쿠폰(정액 3,000원, 수량 10장) */
    private Long saveProductCoupon() {
        LocalDateTime now = now();
        Partnership partnership = Partnership.request(SELLER_ID, INFLUENCER_ID, 20, null, now.minusDays(2));
        partnership.accept(now.minusDays(1));
        partnershipRepository.save(partnership);

        Coupon coupon = Coupon.createProductCoupon(SELLER_ID, "인플루언서 쿠폰", true, 10, now.minusDays(1), now.plusDays(3),
                now.minusDays(1).minusSeconds(1));
        couponRepository.save(coupon);
        productCouponRepository.save(ProductCoupon.create(coupon, partnership.getId(), INFLUENCER_ID, PRODUCT_ID, CODE,
                new ProductCouponDiscount(DiscountType.FIXED, 3_000, null)));
        return coupon.getId();
    }

    private Integer remaining(Long couponId) {
        entityManager.flush();
        entityManager.clear();
        return couponRepository.findById(couponId).orElseThrow().getRemainingQuantity();
    }

    @Test
    @DisplayName("상품 쿠폰과 장바구니 쿠폰을 한 주문에 예약하면 할인액을 돌려주고, 결제 때 둘 다 USED가 된다")
    void reserveBothAndUse() {
        Long productCouponId = saveProductCoupon();
        Long cartCouponId = couponRepository.save(CouponFixtures.activeCartCoupon(null)).getId();
        LocalDateTime now = now();

        long productDiscount = couponApi.reserveProductCoupon(MEMBER_ID, "ordcpn2345", ORDER_ID, ORDER_ITEM_ID, PRODUCT_ID,
                40_000, now);
        long cartDiscount = couponApi.reserveCartCoupon(MEMBER_ID, cartCouponId, ORDER_ID, 37_000, now);
        int used = couponApi.useReservedCoupons(ORDER_ID, now);

        assertThat(productDiscount).isEqualTo(3_000);
        assertThat(cartDiscount).isEqualTo(3_000);   // 3만 원 이상 구간
        assertThat(used).isEqualTo(2);
        assertThat(remaining(productCouponId)).isEqualTo(9);
        assertThat(couponUsageRepository.findAll().stream()
                .filter(usage -> usage.getOrderId().equals(ORDER_ID))
                .map(usage -> usage.getStatus()))
                .containsOnly(CouponUsageStatus.USED);
    }

    @Test
    @DisplayName("한 주문에 상품 쿠폰은 1개까지다: 같은 주문에 두 번째 상품 쿠폰은 409-COUPON_ORDER_LIMIT_EXCEEDED")
    void productCouponOncePerOrder() {
        saveProductCoupon();
        LocalDateTime now = now();
        couponApi.reserveProductCoupon(MEMBER_ID, CODE, ORDER_ID, ORDER_ITEM_ID, PRODUCT_ID, 40_000, now);

        assertThatThrownBy(() -> couponApi.reserveProductCoupon(MEMBER_ID + 1, CODE, ORDER_ID, ORDER_ITEM_ID, PRODUCT_ID, 40_000, now))
                .extracting("resultCode").isEqualTo("409-COUPON_ORDER_LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("결제 전 취소로 해제하면 남은 수량이 돌아오고 같은 회원이 다시 쓸 수 있다")
    void releaseAndReuse() {
        Long couponId = saveProductCoupon();
        LocalDateTime now = now();
        couponApi.reserveProductCoupon(MEMBER_ID, CODE, ORDER_ID, ORDER_ITEM_ID, PRODUCT_ID, 40_000, now);

        int released = couponApi.releaseReservedCoupons(ORDER_ID, now);

        assertThat(released).isEqualTo(1);
        assertThat(remaining(couponId)).isEqualTo(10);
        assertThat(couponApi.reserveProductCoupon(MEMBER_ID, CODE, ORDER_ID + 1, ORDER_ITEM_ID + 1, PRODUCT_ID, 40_000, now))
                .isEqualTo(3_000);
    }

    @Test
    @DisplayName("다른 상품의 주문 항목에 쓰면 400-COUPON_NOT_APPLICABLE이고 수량은 그대로다")
    void otherProduct() {
        Long couponId = saveProductCoupon();

        assertThatThrownBy(() -> couponApi.reserveProductCoupon(MEMBER_ID, CODE, ORDER_ID, ORDER_ITEM_ID, PRODUCT_ID + 1, 40_000, now()))
                .extracting("resultCode").isEqualTo("400-COUPON_NOT_APPLICABLE");
        assertThat(remaining(couponId)).isEqualTo(10);
    }
}
