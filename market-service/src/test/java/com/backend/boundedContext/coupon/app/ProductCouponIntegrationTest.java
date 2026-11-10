package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.CouponFixtures;
import com.backend.boundedContext.coupon.app.dto.AdminCouponSummaryDto;
import com.backend.boundedContext.coupon.app.dto.PartnershipDto;
import com.backend.boundedContext.coupon.app.dto.ProductCouponDto;
import com.backend.boundedContext.coupon.app.dto.ProductCouponIssueCommand;
import com.backend.boundedContext.coupon.app.dto.ProductCouponUpdateCommand;
import com.backend.boundedContext.coupon.app.dto.ProductCouponValidationDto;
import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.CouponDisplayStatus;
import com.backend.boundedContext.coupon.domain.CouponStoppedBy;
import com.backend.boundedContext.coupon.domain.CouponType;
import com.backend.boundedContext.coupon.domain.DiscountType;
import com.backend.boundedContext.coupon.domain.PartnershipEndedBy;
import com.backend.boundedContext.coupon.domain.PartnershipStatus;
import com.backend.boundedContext.coupon.domain.ProductCouponDiscount;
import com.backend.boundedContext.coupon.out.CouponInfluencer;
import com.backend.boundedContext.coupon.out.CouponInfluencerPort;
import com.backend.boundedContext.coupon.out.CouponProductPort;
import com.backend.boundedContext.coupon.out.CouponRepository;
import com.backend.boundedContext.coupon.out.InfluencerPickPort;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.dto.CartLineDto;
import com.backend.shared.order.out.OrderApi;
import com.backend.shared.product.dto.ProductSnapshotDto;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * 협약과 상품 쿠폰을 실제 DB(V6 마이그레이션, JPQL, 벌크 UPDATE, 부분 유니크 인덱스)로 확인한다. 테스트마다 롤백된다.
 * 인플루언서, pick, 상품, 판매자, 장바구니는 다른 컨텍스트라 mock으로 대신한다.
 */
@SpringBootTest
@Transactional
class ProductCouponIntegrationTest {

    private static final long SELLER_MEMBER_ID = 9_300_001L;
    private static final long SELLER_ID = 9_300_002L;
    private static final long INFLUENCER_MEMBER_ID = 9_300_003L;
    private static final long INFLUENCER_ID = 9_300_004L;
    private static final long PRODUCT_ID = 9_300_005L;
    private static final long OTHER_PRODUCT_ID = 9_300_006L;
    private static final long BUYER_ID = 9_300_007L;
    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 100);

    @Autowired private CouponFacade couponFacade;
    @Autowired private CouponRepository couponRepository;
    @Autowired private EntityManager entityManager;

    @MockitoBean private MemberApi memberApi;
    @MockitoBean private OrderApi orderApi;
    @MockitoBean private CouponInfluencerPort couponInfluencerPort;
    @MockitoBean private InfluencerPickPort influencerPickPort;
    @MockitoBean private CouponProductPort couponProductPort;

    @BeforeEach
    void setUp() {
        given(memberApi.getApprovedSellerByMemberId(SELLER_MEMBER_ID))
                .willReturn(new SellerDto(SELLER_ID, SELLER_MEMBER_ID, "팔레트상점", SellerStatus.APPROVED));
        given(memberApi.getSellers(anyCollection()))
                .willReturn(List.of(new SellerDto(SELLER_ID, SELLER_MEMBER_ID, "팔레트상점", SellerStatus.APPROVED)));
        given(couponInfluencerPort.findInfluencer(INFLUENCER_ID))
                .willReturn(Optional.of(new CouponInfluencer(INFLUENCER_ID, INFLUENCER_MEMBER_ID, "뷰티언니", true)));
        given(couponInfluencerPort.getApprovedInfluencerIdByMemberId(INFLUENCER_MEMBER_ID)).willReturn(INFLUENCER_ID);
        given(couponInfluencerPort.getActivityNames(anyCollection())).willReturn(Map.of(INFLUENCER_ID, "뷰티언니"));
        given(influencerPickPort.isPicked(eq(INFLUENCER_ID), anyLong())).willReturn(true);
        List<ProductSnapshotDto> products = List.of(
                new ProductSnapshotDto(PRODUCT_ID, SELLER_ID, "립스틱", 20_000, true, null),
                new ProductSnapshotDto(OTHER_PRODUCT_ID, SELLER_ID, "아이섀도", 30_000, true, null));
        given(couponProductPort.getProducts(anyCollection())).willAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return products.stream().filter(product -> ids.contains(product.productId())).toList();
        });
    }

    private Long activePartnership() {
        Long partnershipId = couponFacade.requestPartnership(SELLER_MEMBER_ID, INFLUENCER_ID, "함께해요");
        couponFacade.acceptPartnership(INFLUENCER_MEMBER_ID, partnershipId);
        return partnershipId;
    }

    private ProductCouponIssueCommand command(Long partnershipId, Long productId, LocalDateTime from, LocalDateTime until) {
        return new ProductCouponIssueCommand(partnershipId, productId, "인플루언서 쿠폰", CouponFixtures.FIXED_3000,
                from, until, true, 100);
    }

    // DB(timestamp(6))를 거쳐도 값이 같도록 초 단위로 자른 현재 시각
    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("요청 → 수락 → 발행하면 판매자, 인플루언서, 관리자 목록에서 같은 쿠폰이 보이고 관리자 필터에서 장바구니 쿠폰은 빠진다")
    void issueAndList() {
        LocalDateTime now = now();
        Long partnershipId = activePartnership();
        Long cartCouponId = couponRepository.save(CouponFixtures.activeCartCoupon(null)).getId();

        Long couponId = couponFacade.issueProductCoupon(SELLER_MEMBER_ID,
                command(partnershipId, PRODUCT_ID, now.minusMinutes(1), now.plusDays(7))).couponId();
        flushAndClear();

        ProductCouponDto detail = couponFacade.getSellerCoupon(SELLER_MEMBER_ID, couponId);
        assertThat(detail.status()).isEqualTo(CouponDisplayStatus.ACTIVE);
        assertThat(detail.code()).hasSize(10).matches("[A-HJ-NP-Z2-9]{10}");
        assertThat(detail.businessName()).isEqualTo("팔레트상점");
        assertThat(detail.activityName()).isEqualTo("뷰티언니");
        assertThat(detail.productName()).isEqualTo("립스틱");
        assertThat(detail.remainingQuantity()).isEqualTo(100);

        assertThat(couponFacade.getSellerCoupons(SELLER_MEMBER_ID, CouponDisplayStatus.ACTIVE, FIRST_PAGE).items())
                .extracting(ProductCouponDto::couponId).containsExactly(couponId);
        assertThat(couponFacade.getSellerCoupons(SELLER_MEMBER_ID, CouponDisplayStatus.SCHEDULED, FIRST_PAGE).items()).isEmpty();
        assertThat(couponFacade.getInfluencerCoupons(INFLUENCER_MEMBER_ID, null, FIRST_PAGE).items())
                .extracting(ProductCouponDto::couponId).containsExactly(couponId);

        List<AdminCouponSummaryDto> bySeller = couponFacade.getCouponsForAdmin(null, null, SELLER_ID, null, FIRST_PAGE).items();
        assertThat(bySeller).extracting(AdminCouponSummaryDto::couponId).containsExactly(couponId).doesNotContain(cartCouponId);
        assertThat(bySeller.getFirst().productCoupon().code()).isEqualTo(detail.code());
        assertThat(couponFacade.getCouponsForAdmin(CouponType.PRODUCT, null, null, INFLUENCER_ID, FIRST_PAGE).items())
                .extracting(AdminCouponSummaryDto::couponId).containsExactly(couponId);
        assertThat(couponFacade.getCouponForAdmin(couponId, FIRST_PAGE).productCoupon().productId()).isEqualTo(PRODUCT_ID);
    }

    @Test
    @DisplayName("같은 인플루언서와 상품으로 기간이 겹치면 409이고, 겹치지 않거나 다른 상품이거나 앞 쿠폰이 중지되면 발행된다")
    void overlap() {
        LocalDateTime now = now();
        Long partnershipId = activePartnership();
        Long first = couponFacade.issueProductCoupon(SELLER_MEMBER_ID,
                command(partnershipId, PRODUCT_ID, now.plusDays(1), now.plusDays(10))).couponId();

        assertThatThrownBy(() -> couponFacade.issueProductCoupon(SELLER_MEMBER_ID,
                command(partnershipId, PRODUCT_ID, now.plusDays(9), now.plusDays(20))))
                .extracting("resultCode").isEqualTo("409-COUPON_PERIOD_OVERLAPPED");

        // 종료 시각은 포함하지 않으므로 앞 쿠폰이 끝나는 시각에 시작하면 겹치지 않는다
        couponFacade.issueProductCoupon(SELLER_MEMBER_ID, command(partnershipId, PRODUCT_ID, now.plusDays(10), now.plusDays(20)));
        couponFacade.issueProductCoupon(SELLER_MEMBER_ID, command(partnershipId, OTHER_PRODUCT_ID, now.plusDays(1), now.plusDays(10)));

        couponFacade.stopCouponBySeller(SELLER_MEMBER_ID, first);
        couponFacade.issueProductCoupon(SELLER_MEMBER_ID, command(partnershipId, PRODUCT_ID, now.plusDays(2), now.plusDays(5)));
    }

    @Test
    @DisplayName("시작 전 쿠폰은 할인과 기간을 바꿀 수 있고, 다른 쿠폰과 겹치게 바꾸면 409")
    void update() {
        LocalDateTime now = now();
        Long partnershipId = activePartnership();
        Long first = couponFacade.issueProductCoupon(SELLER_MEMBER_ID,
                command(partnershipId, PRODUCT_ID, now.plusDays(1), now.plusDays(5))).couponId();
        couponFacade.issueProductCoupon(SELLER_MEMBER_ID, command(partnershipId, PRODUCT_ID, now.plusDays(10), now.plusDays(15)));

        couponFacade.updateProductCoupon(SELLER_MEMBER_ID, first, new ProductCouponUpdateCommand(
                null, DiscountType.RATE, 20L, 5_000L, null, now.plusDays(8), null, null));
        flushAndClear();

        ProductCouponDto updated = couponFacade.getSellerCoupon(SELLER_MEMBER_ID, first);
        assertThat(updated.discountType()).isEqualTo(DiscountType.RATE);
        assertThat(updated.discountValue()).isEqualTo(20);
        assertThat(updated.validUntil()).isEqualTo(now.plusDays(8));

        assertThatThrownBy(() -> couponFacade.updateProductCoupon(SELLER_MEMBER_ID, first,
                new ProductCouponUpdateCommand(null, null, null, null, null, now.plusDays(11), null, null)))
                .extracting("resultCode").isEqualTo("409-COUPON_PERIOD_OVERLAPPED");
    }

    @Test
    @DisplayName("협약을 종료하면 그 협약의 시작 전, 사용 가능 쿠폰이 모두 SYSTEM 중지되고 새 발행은 409-PARTNERSHIP_NOT_ACTIVE")
    void endPartnershipStopsCoupons() {
        LocalDateTime now = now();
        Long partnershipId = activePartnership();
        Long active = couponFacade.issueProductCoupon(SELLER_MEMBER_ID,
                command(partnershipId, PRODUCT_ID, now.minusMinutes(1), now.plusDays(3))).couponId();
        Long scheduled = couponFacade.issueProductCoupon(SELLER_MEMBER_ID,
                command(partnershipId, OTHER_PRODUCT_ID, now.plusDays(1), now.plusDays(3))).couponId();

        couponFacade.endPartnershipByInfluencer(INFLUENCER_MEMBER_ID, partnershipId);
        flushAndClear();

        for (Long couponId : List.of(active, scheduled)) {
            Coupon coupon = couponRepository.findById(couponId).orElseThrow();
            assertThat(coupon.displayStatus(LocalDateTime.now())).isEqualTo(CouponDisplayStatus.STOPPED);
            assertThat(coupon.getStoppedBy()).isEqualTo(CouponStoppedBy.SYSTEM);
        }
        assertThat(couponFacade.getSellerPartnerships(SELLER_MEMBER_ID, null, FIRST_PAGE).items())
                .extracting(PartnershipDto::endedBy).containsExactly(PartnershipEndedBy.INFLUENCER);
        assertThatThrownBy(() -> couponFacade.issueProductCoupon(SELLER_MEMBER_ID,
                command(partnershipId, PRODUCT_ID, now.plusDays(5), now.plusDays(6))))
                .extracting("resultCode").isEqualTo("409-PARTNERSHIP_NOT_ACTIVE");
    }

    @Test
    @DisplayName("상품 판매가 끝나면 그 상품의 쿠폰만 중지되고, 끝난 쿠폰과 다른 상품 쿠폰은 그대로다")
    void stopByProducts() {
        LocalDateTime now = now();
        Long partnershipId = activePartnership();
        Long target = couponFacade.issueProductCoupon(SELLER_MEMBER_ID,
                command(partnershipId, PRODUCT_ID, now.minusMinutes(1), now.plusDays(3))).couponId();
        Long other = couponFacade.issueProductCoupon(SELLER_MEMBER_ID,
                command(partnershipId, OTHER_PRODUCT_ID, now.minusMinutes(1), now.plusDays(3))).couponId();

        int stopped = couponFacade.stopProductCouponsByProducts(List.of(PRODUCT_ID), "상품 판매 중지");
        int again = couponFacade.stopProductCouponsByProducts(List.of(PRODUCT_ID), "상품 판매 중지");
        flushAndClear();

        assertThat(stopped).isEqualTo(1);
        assertThat(again).isZero();
        assertThat(couponRepository.findById(target).orElseThrow().getStopReason()).isEqualTo("상품 판매 중지");
        assertThat(couponRepository.findById(other).orElseThrow().displayStatus(LocalDateTime.now()))
                .isEqualTo(CouponDisplayStatus.ACTIVE);
    }

    @Test
    @DisplayName("판매자 제재: 요청 중, 진행 중 협약이 모두 종료되고 쿠폰이 중지된다")
    void sellerSanction() {
        LocalDateTime now = now();
        Long partnershipId = activePartnership();
        Long couponId = couponFacade.issueProductCoupon(SELLER_MEMBER_ID,
                command(partnershipId, PRODUCT_ID, now.minusMinutes(1), now.plusDays(3))).couponId();

        int ended = couponFacade.endPartnershipsBySellerSanction(SELLER_ID);
        flushAndClear();

        assertThat(ended).isEqualTo(1);
        assertThat(couponFacade.getPartnershipsForAdmin(SELLER_ID, null, PartnershipStatus.ENDED, FIRST_PAGE).items())
                .extracting(PartnershipDto::endedBy).containsExactly(PartnershipEndedBy.SYSTEM);
        assertThat(couponRepository.findById(couponId).orElseThrow().getStoppedBy()).isEqualTo(CouponStoppedBy.SYSTEM);
        assertThat(couponFacade.endPartnershipsBySellerSanction(SELLER_ID)).isZero();
    }

    @Test
    @DisplayName("같은 인플루언서에게 협약을 다시 요청하면 409이고, 거절된 뒤에는 다시 요청할 수 있다")
    void duplicatePartnership() {
        Long first = couponFacade.requestPartnership(SELLER_MEMBER_ID, INFLUENCER_ID, null);

        assertThatThrownBy(() -> couponFacade.requestPartnership(SELLER_MEMBER_ID, INFLUENCER_ID, null))
                .extracting("resultCode").isEqualTo("409-PARTNERSHIP_ALREADY_EXISTS");

        couponFacade.rejectPartnership(INFLUENCER_MEMBER_ID, first);
        Long second = couponFacade.requestPartnership(SELLER_MEMBER_ID, INFLUENCER_ID, null);

        assertThat(second).isNotEqualTo(first);
        assertThat(couponFacade.getInfluencerPartnerships(INFLUENCER_MEMBER_ID, null, FIRST_PAGE).items())
                .extracting(PartnershipDto::status).containsExactly(PartnershipStatus.REQUESTED, PartnershipStatus.REJECTED);
    }

    @Test
    @DisplayName("코드 검증: 구매자가 지정한 대상 상품 항목의 예상 할인액을 주고 예약은 하지 않는다")
    void validateCode() {
        LocalDateTime now = now();
        Long partnershipId = activePartnership();
        String code = couponFacade.issueProductCoupon(SELLER_MEMBER_ID, new ProductCouponIssueCommand(partnershipId, PRODUCT_ID,
                "쿠폰", new ProductCouponDiscount(DiscountType.RATE, 10, 3_000L), now.minusMinutes(1), now.plusDays(3),
                false, null)).code();
        given(orderApi.getCartLines(eq(BUYER_ID), any())).willReturn(List.of(new CartLineDto(1L, PRODUCT_ID, 20_000, 2, true)));

        ProductCouponValidationDto result = couponFacade.validateProductCouponCode(BUYER_ID, code.toLowerCase(), 1L);

        assertThat(result.cartItemId()).isEqualTo(1L);
        assertThat(result.expectedDiscountAmount()).isEqualTo(3_000);   // 40,000의 10% = 4,000 → 최대 3,000
        assertThat(couponFacade.getMyCouponUsages(BUYER_ID, FIRST_PAGE).items()).isEmpty();
    }
}
