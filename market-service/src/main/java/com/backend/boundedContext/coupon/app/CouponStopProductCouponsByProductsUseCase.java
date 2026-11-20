package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.out.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;

/**
 * 판매를 멈춘(판매 중지, 삭제) 상품의 상품 쿠폰 자동 중지 (CouponApi, 상품 컨텍스트가 같은 트랜잭션에서 부른다).
 * 아직 끝나지 않은 운영 중 쿠폰만 중지(SYSTEM)하고 중지한 수를 돌려준다. 다시 불러도 결과가 같다.
 */
@Component
@RequiredArgsConstructor
public class CouponStopProductCouponsByProductsUseCase {

    private final CouponRepository couponRepository;

    public int stop(Collection<Long> productIds, String reason) {
        if (productIds.isEmpty()) {
            return 0;   // 빈 IN 절 방지
        }
        return couponRepository.stopActiveProductCouponsByProductIds(productIds, reason, LocalDateTime.now());
    }
}
