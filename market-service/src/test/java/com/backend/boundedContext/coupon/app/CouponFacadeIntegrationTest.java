package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.CouponFixtures;
import com.backend.boundedContext.coupon.app.dto.AdminCouponDetailDto;
import com.backend.boundedContext.coupon.app.dto.AdminCouponSummaryDto;
import com.backend.boundedContext.coupon.app.dto.CartCouponCreateCommand;
import com.backend.boundedContext.coupon.app.dto.CartCouponDto;
import com.backend.boundedContext.coupon.app.dto.CartCouponsDto;
import com.backend.boundedContext.coupon.app.dto.MyCouponUsageDto;
import com.backend.boundedContext.coupon.app.dto.CartCouponTierDto;
import com.backend.boundedContext.coupon.domain.CartCouponTierSpec;
import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.CouponDisplayStatus;
import com.backend.boundedContext.coupon.domain.CouponStoppedBy;
import com.backend.boundedContext.coupon.domain.CouponType;
import com.backend.boundedContext.coupon.domain.CouponUsageStatus;
import com.backend.boundedContext.coupon.out.CouponRepository;
import com.backend.global.page.PageResponse;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.out.OrderApi;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * 마이그레이션(V4)과 JPQL, 상태 계산 조건(CouponSpecifications)이 실제 DB에서 맞게 도는지 Facade로 확인한다.
 * 테스트마다 롤백된다.
 */
@SpringBootTest
@Transactional
class CouponFacadeIntegrationTest {

    private static final long MEMBER_ID = 8_001L;
    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 100);

    @Autowired
    private CouponFacade couponFacade;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private MemberApi memberApi;

    /** 장바구니 금액 계산을 주문 쪽 구현과 분리하고, 다른 통합 테스트와 mock 조합을 맞춰 테스트 컨텍스트를 같이 쓴다 */
    @MockitoBean
    private OrderApi orderApi;

    @Test
    @DisplayName("장바구니 쿠폰을 등록하면 구간이 최소 주문 금액 순으로 저장되고, 관리자 상세에서 사용 가능 상태로 보인다")
    void createAndGetDetail() {
        LocalDateTime now = LocalDateTime.now();
        Long couponId = couponFacade.createCartCoupon(1L, new CartCouponCreateCommand("11월 장바구니 쿠폰",
                List.of(new CartCouponTierSpec(70_000, 7_000), new CartCouponTierSpec(30_000, 3_000)),
                true, 100, now.minusMinutes(1), now.plusDays(3)));
        entityManager.flush();
        entityManager.clear();

        AdminCouponDetailDto detail = couponFacade.getCouponForAdmin(couponId, FIRST_PAGE);

        assertThat(detail.couponType()).isEqualTo(CouponType.CART);
        assertThat(detail.status()).isEqualTo(CouponDisplayStatus.ACTIVE);
        assertThat(detail.tiers()).extracting(CartCouponTierDto::minOrderAmount).containsExactly(30_000L, 70_000L);
        assertThat(detail.totalQuantity()).isEqualTo(100);
        assertThat(detail.remainingQuantity()).isEqualTo(100);
        assertThat(detail.usedCount()).isZero();
        assertThat(detail.usages().items()).isEmpty();
    }

    @Test
    @DisplayName("관리자 목록의 상태 필터는 저장된 운영 상태와 유효 기간으로 SCHEDULED, ACTIVE, ENDED, STOPPED를 가른다")
    void adminListStatusFilter() {
        LocalDateTime now = LocalDateTime.now();
        Long scheduled = couponRepository.save(CouponFixtures.cartCoupon("시작 전", now.plusDays(1), now.plusDays(2), null)).getId();
        Long active = couponRepository.save(CouponFixtures.cartCoupon("사용 가능", now.minusDays(1), now.plusDays(1), null)).getId();
        Long ended = couponRepository.save(CouponFixtures.cartCoupon("종료", now.minusDays(3), now.minusDays(1), null)).getId();
        Coupon stoppedCoupon = CouponFixtures.cartCoupon("중지", now.minusDays(1), now.plusDays(1), null);
        stoppedCoupon.stop(CouponStoppedBy.ADMIN, "중지", now);
        Long stopped = couponRepository.save(stoppedCoupon).getId();
        List<Long> mine = List.of(scheduled, active, ended, stopped);

        assertThat(idsOf(CouponDisplayStatus.SCHEDULED, mine)).containsExactly(scheduled);
        assertThat(idsOf(CouponDisplayStatus.ACTIVE, mine)).containsExactly(active);
        assertThat(idsOf(CouponDisplayStatus.ENDED, mine)).containsExactly(ended);
        assertThat(idsOf(CouponDisplayStatus.STOPPED, mine)).containsExactly(stopped);
        assertThat(idsOf(null, mine)).containsExactlyInAnyOrderElementsOf(mine);
        assertThat(couponFacade.getCouponsForAdmin(CouponType.PRODUCT, null, null, null, FIRST_PAGE).items())
                .extracting(AdminCouponSummaryDto::couponId).doesNotContainAnyElementsOf(mine);
    }

    // 테스트가 만든 쿠폰 중 이 상태로 조회되는 것 (다른 데이터가 있어도 영향받지 않게)
    private List<Long> idsOf(CouponDisplayStatus status, List<Long> mine) {
        PageResponse<AdminCouponSummaryDto> page = couponFacade.getCouponsForAdmin(CouponType.CART, status, null, null, FIRST_PAGE);
        return page.items().stream()
                .map(AdminCouponSummaryDto::couponId)
                .filter(mine::contains)
                .toList();
    }

    @Test
    @DisplayName("구매자 장바구니 쿠폰 목록에는 지금 쓸 수 있는 쿠폰만 나오고, 장바구니 금액으로 할인액을 계산한다")
    void cartCoupons() {
        LocalDateTime now = LocalDateTime.now();
        Long active = couponRepository.save(CouponFixtures.cartCoupon("사용 가능", now.minusDays(1), now.plusDays(1), null)).getId();
        Long scheduled = couponRepository.save(CouponFixtures.cartCoupon("시작 전", now.plusDays(1), now.plusDays(2), null)).getId();
        given(orderApi.getCartItemsAmount(MEMBER_ID, List.of(1L, 2L))).willReturn(62_000L);

        CartCouponsDto result = couponFacade.getCartCoupons(MEMBER_ID, List.of(1L, 2L));

        assertThat(result.orderAmount()).isEqualTo(62_000L);
        assertThat(result.coupons()).extracting(CartCouponDto::couponId).contains(active).doesNotContain(scheduled);
        CartCouponDto mine = result.coupons().stream().filter(c -> c.couponId().equals(active)).findFirst().orElseThrow();
        assertThat(mine.expectedDiscountAmount()).isEqualTo(5_000L);
        assertThat(mine.applicable()).isTrue();
        assertThat(mine.used()).isFalse();
    }

    @Test
    @DisplayName("예약 → 결제로 사용 처리하면 내 사용 내역과 관리자 사용 집계에 남고, 같은 쿠폰은 다시 쓸 수 없다")
    void reserveAndUse() {
        LocalDateTime now = LocalDateTime.now();
        Long couponId = couponRepository.save(CouponFixtures.cartCoupon("결제 쿠폰", now.minusDays(1), now.plusDays(1), 10)).getId();

        long discount = couponFacade.reserveCartCoupon(MEMBER_ID, couponId, 900_001L, 55_000, now);
        couponFacade.useReservedCoupons(900_001L, now);
        entityManager.flush();
        entityManager.clear();

        assertThat(discount).isEqualTo(5_000);
        assertThat(couponRepository.findById(couponId).orElseThrow().getRemainingQuantity()).isEqualTo(9);

        PageResponse<MyCouponUsageDto> usages = couponFacade.getMyCouponUsages(MEMBER_ID, FIRST_PAGE);
        assertThat(usages.items()).hasSize(1);
        assertThat(usages.items().getFirst().couponName()).isEqualTo("결제 쿠폰");
        assertThat(usages.items().getFirst().couponType()).isEqualTo(CouponType.CART);
        assertThat(usages.items().getFirst().status()).isEqualTo(CouponUsageStatus.USED);

        AdminCouponDetailDto detail = couponFacade.getCouponForAdmin(couponId, FIRST_PAGE);
        assertThat(detail.usedCount()).isEqualTo(1);
        assertThat(detail.usedDiscountAmount()).isEqualTo(5_000);
        assertThat(detail.usages().items()).hasSize(1);

        assertThatThrownBy(() -> couponFacade.reserveCartCoupon(MEMBER_ID, couponId, 900_002L, 55_000, now))
                .extracting("resultCode").isEqualTo("409-COUPON_ALREADY_USED");
        assertThat(couponFacade.getCartCoupons(MEMBER_ID, null).coupons())
                .filteredOn(c -> c.couponId().equals(couponId))
                .extracting(CartCouponDto::used).containsExactly(true);
    }

    @Test
    @DisplayName("예약을 해제하면 남은 수량이 돌아오고, 같은 회원이 그 쿠폰을 다시 예약할 수 있다")
    void releaseAndReserveAgain() {
        LocalDateTime now = LocalDateTime.now();
        Long couponId = couponRepository.save(CouponFixtures.cartCoupon("해제 쿠폰", now.minusDays(1), now.plusDays(1), 10)).getId();

        couponFacade.reserveCartCoupon(MEMBER_ID, couponId, 900_011L, 30_000, now);
        int released = couponFacade.releaseReservedCoupons(900_011L, now);
        entityManager.flush();
        entityManager.clear();

        assertThat(released).isEqualTo(1);
        assertThat(couponRepository.findById(couponId).orElseThrow().getRemainingQuantity()).isEqualTo(10);

        couponFacade.reserveCartCoupon(MEMBER_ID, couponId, 900_012L, 30_000, now);
        entityManager.flush();
        entityManager.clear();

        assertThat(couponRepository.findById(couponId).orElseThrow().getRemainingQuantity()).isEqualTo(9);
        assertThat(couponFacade.getMyCouponUsages(MEMBER_ID, FIRST_PAGE).items())
                .extracting(MyCouponUsageDto::status)
                .containsExactly(CouponUsageStatus.RESERVED, CouponUsageStatus.RELEASED);   // 최신순
    }

    @Test
    @DisplayName("관리자가 중지한 쿠폰은 409-COUPON_NOT_ACTIVE로 예약되지 않고, 다시 중지하면 409-COUPON_STATE_INVALID")
    void stopByAdmin() {
        LocalDateTime now = LocalDateTime.now();
        Long couponId = couponRepository.save(CouponFixtures.cartCoupon("중지할 쿠폰", now.minusDays(1), now.plusDays(1), null)).getId();

        couponFacade.stopCouponByAdmin(couponId, "부정 사용");

        assertThat(couponFacade.getCouponForAdmin(couponId, FIRST_PAGE).status()).isEqualTo(CouponDisplayStatus.STOPPED);
        assertThatThrownBy(() -> couponFacade.reserveCartCoupon(MEMBER_ID, couponId, 900_021L, 50_000, LocalDateTime.now()))
                .extracting("resultCode").isEqualTo("409-COUPON_NOT_ACTIVE");
        assertThatThrownBy(() -> couponFacade.stopCouponByAdmin(couponId, "다시"))
                .extracting("resultCode").isEqualTo("409-COUPON_STATE_INVALID");
    }

    @Test
    @DisplayName("예약한 뒤 관리자가 쿠폰을 중지하면 결제 때 사용 처리가 409-COUPON_NOT_ACTIVE로 막힌다")
    void useAfterStoppedByAdmin() {
        LocalDateTime now = LocalDateTime.now();
        Long couponId = couponRepository.save(CouponFixtures.cartCoupon("예약 뒤 중지", now.minusDays(1), now.plusDays(1), null)).getId();
        couponFacade.reserveCartCoupon(MEMBER_ID, couponId, 900_031L, 50_000, now);

        couponFacade.stopCouponByAdmin(couponId, "부정 사용");

        assertThatThrownBy(() -> couponFacade.useReservedCoupons(900_031L, LocalDateTime.now()))
                .extracting("resultCode").isEqualTo("409-COUPON_NOT_ACTIVE");
    }

    @Test
    @DisplayName("예약한 뒤 결제 전에 쿠폰 유효 기간이 끝나면 결제 때 사용 처리가 409-COUPON_NOT_ACTIVE로 막힌다(종료 시각 포함)")
    void useAfterCouponExpired() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime validUntil = now.plusMinutes(10);
        Long couponId = couponRepository.save(CouponFixtures.cartCoupon("곧 끝나는 쿠폰", now.minusDays(1), validUntil, null)).getId();
        couponFacade.reserveCartCoupon(MEMBER_ID, couponId, 900_041L, 50_000, now);

        // 종료 시각은 포함하지 않으므로(validFrom <= 현재 < validUntil) 종료 시각 정각의 결제도 막힌다
        assertThatThrownBy(() -> couponFacade.useReservedCoupons(900_041L, validUntil))
                .extracting("resultCode").isEqualTo("409-COUPON_NOT_ACTIVE");
    }

    @Test
    @DisplayName("예약한 쿠폰이 결제 시각에도 유효 기간 안이면 사용 처리된다(종료 직전)")
    void useJustBeforeCouponExpires() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime validUntil = now.plusMinutes(10);
        Long couponId = couponRepository.save(CouponFixtures.cartCoupon("종료 직전 결제", now.minusDays(1), validUntil, null)).getId();
        couponFacade.reserveCartCoupon(MEMBER_ID, couponId, 900_042L, 50_000, now);

        assertThat(couponFacade.useReservedCoupons(900_042L, validUntil.minusSeconds(1))).isEqualTo(1);
    }

    @Test
    @DisplayName("없는 쿠폰 상세는 404-COUPON_NOT_FOUND")
    void notFound() {
        assertThatThrownBy(() -> couponFacade.getCouponForAdmin(Long.MAX_VALUE, FIRST_PAGE))
                .extracting("resultCode").isEqualTo("404-COUPON_NOT_FOUND");
    }
}
