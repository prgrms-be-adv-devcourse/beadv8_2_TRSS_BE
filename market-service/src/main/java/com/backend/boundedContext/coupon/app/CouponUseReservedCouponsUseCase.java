package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.domain.CouponErrorCode;
import com.backend.boundedContext.coupon.domain.CouponUsageStatus;
import com.backend.boundedContext.coupon.out.CouponUsageRepository;
import com.backend.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 결제 성공 → 주문에 예약된 쿠폰을 모두 사용 처리 (OrderPayOrderUseCase가 결제 요청 전에 부름)
 * 예약이 없거나 이미 처리됐으면 아무것도 하지 않는다. 처리한 수를 돌려준다.
 * 상태가 아직 RESERVED인 행만 바꾸는 조건부 UPDATE라, 동시에 해제(취소·만료)가 먼저 처리한 기록은 USED로 덮어쓰지 않는다.
 *
 * 예약 뒤 관리자가 쿠폰을 중지했거나 유효 기간이 끝났으면 결제를 막는다: 지금 쓸 수 있는 쿠폰의 예약만 사용 처리하고, 예약 수보다 적게 바뀌었으면
 * 409-COUPON_NOT_ACTIVE를 던진다. 결제 트랜잭션에 참여하므로 결제(주문 PAID·결제 기록)와 이미 바꾼 사용 처리가 함께 롤백된다.
 * 쿠폰 상태와 기간은 UPDATE 조건으로 보므로(결제 시각 now 기준), 예약 수를 센 뒤 중지가 커밋된 경우도 막힌다.
 * (같은 주문의 결제와 취소·만료는 주문 쪽이 주문 상태 조건부 UPDATE로 먼저 막으므로, 그사이 예약이 해제돼 수가 어긋나는 일은 없다고 본다)
 */
@Component
@RequiredArgsConstructor
public class CouponUseReservedCouponsUseCase {

    private final CouponUsageRepository couponUsageRepository;

    public int use(Long orderId, LocalDateTime now) {
        long reserved = couponUsageRepository.countByOrderIdAndStatus(orderId, CouponUsageStatus.RESERVED);
        int used = couponUsageRepository.markUsedIfReservedAndCouponUsable(orderId, now);
        if (used < reserved) {
            // 예약 뒤 중지됐거나 유효 기간이 끝난 쿠폰이 있다. 결제 트랜잭션 전체를 롤백시킨다
            throw new DomainException(CouponErrorCode.COUPON_NOT_ACTIVE);
        }
        return used;
    }
}
