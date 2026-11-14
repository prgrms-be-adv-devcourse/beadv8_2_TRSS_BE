package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.domain.CouponUsage;
import com.backend.boundedContext.coupon.domain.CouponUsageStatus;
import com.backend.boundedContext.coupon.out.CouponRepository;
import com.backend.boundedContext.coupon.out.CouponUsageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 결제 전 취소나 만료 → 주문에 예약된 쿠폰을 모두 해제하고, 수량 제한 쿠폰은 남은 수량을 되돌린다 (OrderCancelSupport에서 부름)
 * 해제하면 같은 회원이 그 쿠폰을 다시 쓸 수 있다. 예약이 없거나 이미 처리됐으면 아무것도 하지 않는다. 처리한 수를 돌려준다.
 * 기록마다 상태가 아직 RESERVED일 때만 RELEASED로 바꾸는 조건부 UPDATE를 하고, 실제로 바꾼 기록만 수량을 되돌린다.
 * 그래서 취소와 만료가 동시에 와도 한 예약에 수량은 한 번만 돌아가고, 결제 성공(USED)이 먼저 커밋한 기록은 해제하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class CouponReleaseReservedCouponsUseCase {

    private final CouponUsageRepository couponUsageRepository;
    private final CouponRepository couponRepository;

    public int release(Long orderId, LocalDateTime now) {
        List<CouponUsage> reserved = couponUsageRepository.findAllWithCouponByOrderIdAndStatus(orderId, CouponUsageStatus.RESERVED);

        int released = 0;
        for (CouponUsage usage : reserved) {
            if (couponUsageRepository.markReleasedIfReserved(usage.getId(), now) == 0) continue;   // 다른 쪽이 먼저 처리함
            released++;
            if (usage.getCoupon().isQuantityLimited()) {
                couponRepository.increaseRemainingQuantity(usage.getCoupon().getId());
            }
        }
        return released;
    }
}
