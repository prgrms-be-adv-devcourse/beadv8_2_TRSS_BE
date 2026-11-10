package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.app.dto.AdminCouponSummaryDto;
import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.CouponDisplayStatus;
import com.backend.boundedContext.coupon.domain.CouponType;
import com.backend.boundedContext.coupon.out.CouponRepository;
import com.backend.boundedContext.coupon.out.CouponSpecifications;
import com.backend.boundedContext.coupon.out.CouponUsageStat;
import com.backend.global.page.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 관리자 전체 쿠폰 목록 (최신 등록순)
 * 보내지 않은 조건은 적용하지 않는다. 상태는 조회 시점 기준으로 계산한 화면 상태로 거른다.
 */
@Component
@RequiredArgsConstructor
public class CouponGetAdminCouponsUseCase {

    private final CouponRepository couponRepository;
    private final CouponSupport couponSupport;

    public PageResponse<AdminCouponSummaryDto> getCoupons(CouponType type, CouponDisplayStatus status, Pageable pageable) {
        LocalDateTime now = LocalDateTime.now();
        // 요청의 sort는 무시하고 최신 등록순으로만 준다
        Pageable latestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "id"));

        Page<Coupon> coupons = couponRepository.findAll(
                CouponSpecifications.typeEquals(type).and(CouponSpecifications.displayStatusEquals(status, now)),
                latestFirst);

        Map<Long, CouponUsageStat> usedStats = couponSupport.getUsedStats(
                coupons.getContent().stream().map(Coupon::getId).toList());

        return PageResponse.from(coupons, coupon -> AdminCouponSummaryDto.of(coupon, now,
                usedStats.getOrDefault(coupon.getId(), CouponUsageStat.empty(coupon.getId()))));
    }
}
