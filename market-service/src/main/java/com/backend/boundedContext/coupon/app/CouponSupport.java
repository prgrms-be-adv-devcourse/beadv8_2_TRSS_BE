package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.CouponErrorCode;
import com.backend.boundedContext.coupon.domain.CouponUsageStatus;
import com.backend.boundedContext.coupon.out.CouponRepository;
import com.backend.boundedContext.coupon.out.CouponUsageRepository;
import com.backend.boundedContext.coupon.out.CouponUsageStat;
import com.backend.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 여러 UseCase가 함께 쓰는 조회
 */
@Component
@RequiredArgsConstructor
public class CouponSupport {

    private final CouponRepository couponRepository;
    private final CouponUsageRepository couponUsageRepository;

    // 쿠폰 조회. 없으면 404-COUPON_NOT_FOUND
    public Coupon getCoupon(Long couponId) {
        return couponRepository.findById(couponId)
                .orElseThrow(() -> new DomainException(CouponErrorCode.COUPON_NOT_FOUND));
    }

    /** 쿠폰을 쓰기 잠금해 조회한다. 확인한 상태가 트랜잭션이 끝날 때까지 바뀌지 않아야 할 때 (예약: 관리자 중지와 겹치지 않게) */
    public Coupon getCouponForUpdate(Long couponId) {
        return couponRepository.findByIdForUpdate(couponId)
                .orElseThrow(() -> new DomainException(CouponErrorCode.COUPON_NOT_FOUND));
    }

    // 쿠폰별 사용(USED) 수와 할인 합계. 사용 기록이 없는 쿠폰은 결과에 없으므로 getOrDefault로 꺼낸다
    public Map<Long, CouponUsageStat> getUsedStats(Collection<Long> couponIds) {
        if (couponIds.isEmpty()) {
            return Map.of();   // 빈 IN 절 방지
        }

        return couponUsageRepository.sumByCouponIdsAndStatus(couponIds, CouponUsageStatus.USED).stream()
                .collect(Collectors.toMap(CouponUsageStat::couponId, Function.identity()));
    }
}
