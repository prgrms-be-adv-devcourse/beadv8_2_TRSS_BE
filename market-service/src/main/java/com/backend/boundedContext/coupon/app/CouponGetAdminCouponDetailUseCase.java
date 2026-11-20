package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.app.dto.AdminCouponDetailDto;
import com.backend.boundedContext.coupon.app.dto.AdminCouponUsageDto;
import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.out.CouponUsageRepository;
import com.backend.boundedContext.coupon.out.CouponUsageStat;
import com.backend.global.page.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자 쿠폰 상세 (구간 또는 대상 상품과 코드, 사용 내역)
 * 사용 내역은 최신순이고 page와 size로 나눠 조회한다. 없는 쿠폰은 404-COUPON_NOT_FOUND.
 */
@Component
@RequiredArgsConstructor
public class CouponGetAdminCouponDetailUseCase {

    private final CouponSupport couponSupport;
    private final CouponUsageRepository couponUsageRepository;

    public AdminCouponDetailDto getCoupon(Long couponId, Pageable usagePageable) {
        LocalDateTime now = LocalDateTime.now();
        Coupon coupon = couponSupport.getCoupon(couponId);

        CouponUsageStat usedStat = couponSupport.getUsedStats(List.of(couponId))
                .getOrDefault(couponId, CouponUsageStat.empty(couponId));

        Pageable latestFirst = PageRequest.of(usagePageable.getPageNumber(), usagePageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "id"));
        PageResponse<AdminCouponUsageDto> usages = PageResponse.from(
                couponUsageRepository.findAllByCouponId(couponId, latestFirst), AdminCouponUsageDto::from);

        return AdminCouponDetailDto.of(coupon, now, usedStat,
                couponSupport.getProductCouponInfos(List.of(couponId)).get(couponId), usages);
    }
}
