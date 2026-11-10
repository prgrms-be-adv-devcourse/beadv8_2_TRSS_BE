package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.app.dto.MyCouponUsageDto;
import com.backend.boundedContext.coupon.out.CouponUsageRepository;
import com.backend.global.page.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * 내 쿠폰 사용 내역 (최신순). 예약, 사용, 해제 기록을 모두 보여 준다
 */
@Component
@RequiredArgsConstructor
public class CouponGetMyUsagesUseCase {

    private final CouponUsageRepository couponUsageRepository;

    public PageResponse<MyCouponUsageDto> getUsages(Long memberId, Pageable pageable) {
        Pageable latestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "id"));

        return PageResponse.from(couponUsageRepository.findAllByMemberId(memberId, latestFirst), MyCouponUsageDto::from);
    }
}
