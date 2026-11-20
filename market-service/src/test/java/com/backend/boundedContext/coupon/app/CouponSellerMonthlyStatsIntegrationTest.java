package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.app.dto.SellerCouponMonthlyStatsDto;
import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.CouponUsage;
import com.backend.boundedContext.coupon.out.CouponRepository;
import com.backend.boundedContext.coupon.out.CouponUsageRepository;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
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
import java.time.YearMonth;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 판매자 쿠폰 한 달 사용 집계 쿼리를 실제 DB로 확인한다 (테스트가 끝나면 롤백)
 * 그 달에 USED된 내 판매자 쿠폰만 세고, 다른 달, 예약 중, 다른 판매자 쿠폰은 세지 않는다
 */
@SpringBootTest
@Transactional
class CouponSellerMonthlyStatsIntegrationTest {

    @Autowired private CouponFacade couponFacade;
    @Autowired private CouponRepository couponRepository;
    @Autowired private CouponUsageRepository couponUsageRepository;
    @Autowired private EntityManager entityManager;

    @MockitoBean private MemberApi memberApi;
    @MockitoBean private OrderApi orderApi;

    @Test
    @DisplayName("그 달에 사용된 내 쿠폰의 횟수와 할인 합계만 센다")
    void countsOnlyUsedInMonthForSeller() {
        long base = 9_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000L) * 100L;
        long sellerId = base;
        long otherSellerId = base + 1;
        long sellerMemberId = base + 2;
        given(memberApi.getApprovedSellerByMemberId(sellerMemberId))
                .willReturn(new SellerDto(sellerId, sellerMemberId, "팔레트상점", SellerStatus.APPROVED));

        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime until = LocalDateTime.of(2027, 1, 1, 0, 0);
        Coupon mine = couponRepository.save(Coupon.createProductCoupon(sellerId, "내 쿠폰", false, null,
                from, until, from.minusSeconds(1)));
        Coupon others = couponRepository.save(Coupon.createProductCoupon(otherSellerId, "다른 판매자 쿠폰", false, null,
                from, until, from.minusSeconds(1)));

        used(mine, base + 10, 3_000L, LocalDateTime.of(2026, 11, 1, 0, 0));      // 11월 첫 순간: 포함
        used(mine, base + 11, 2_000L, LocalDateTime.of(2026, 11, 30, 23, 59));   // 11월 마지막: 포함
        used(mine, base + 12, 9_000L, LocalDateTime.of(2026, 10, 31, 23, 59));   // 10월: 제외
        used(mine, base + 13, 7_000L, LocalDateTime.of(2026, 12, 1, 0, 0));      // 12월 첫 순간: 제외
        reserved(mine, base + 14, 5_000L, LocalDateTime.of(2026, 11, 10, 12, 0)); // 예약 중: 제외
        used(others, base + 15, 4_000L, LocalDateTime.of(2026, 11, 10, 12, 0));  // 다른 판매자: 제외
        entityManager.flush();
        entityManager.clear();

        SellerCouponMonthlyStatsDto stats = couponFacade.getSellerCouponMonthlyStats(sellerMemberId, YearMonth.of(2026, 11));

        assertThat(stats.month()).isEqualTo("2026-11");
        assertThat(stats.usedCount()).isEqualTo(2L);
        assertThat(stats.usedDiscountAmount()).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("사용 내역이 없으면 0으로 돌려준다")
    void zeroWhenNoUsage() {
        long base = 9_100_000_000L + ThreadLocalRandom.current().nextLong(1_000_000L) * 100L;
        given(memberApi.getApprovedSellerByMemberId(base + 2))
                .willReturn(new SellerDto(base, base + 2, "팔레트상점", SellerStatus.APPROVED));

        SellerCouponMonthlyStatsDto stats = couponFacade.getSellerCouponMonthlyStats(base + 2, YearMonth.of(2026, 11));

        assertThat(stats.usedCount()).isZero();
        assertThat(stats.usedDiscountAmount()).isZero();
    }

    private void used(Coupon coupon, long id, long discount, LocalDateTime usedAt) {
        CouponUsage usage = CouponUsage.reserve(coupon, id, id, id, discount, usedAt.minusMinutes(5));
        usage.use(usedAt);
        couponUsageRepository.save(usage);
    }

    private void reserved(Coupon coupon, long id, long discount, LocalDateTime reservedAt) {
        couponUsageRepository.save(CouponUsage.reserve(coupon, id, id, id, discount, reservedAt));
    }
}
