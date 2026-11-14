package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.domain.CouponErrorCode;
import com.backend.boundedContext.coupon.out.CouponUsageRepository;
import com.backend.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 주문에 예약된 쿠폰의 결제 시작 확인과 결제 성공 사용 처리
 *
 * 결제는 HTTP라 "결제 시작(주문 PAYING) → 결제 요청 → 결과 반영"이 트랜잭션 두 개로 나뉜다.
 * - validateUsable: 결제 시작 트랜잭션에서 부른다. 예약 뒤 관리자가 쿠폰을 중지했거나 유효 기간이 끝났으면 409-COUPON_NOT_ACTIVE로
 *   돈이 움직이기 전에 멈춘다. 쿠폰 조건은 Coupon.isUsableAt과 같다
 * - use: 결제 결과가 PAID일 때 반영 트랜잭션에서 부른다. 쿠폰 상태·기간은 보지 않는다. 결제 시작 때 확인했고 예약이 할인을 보장하므로,
 *   결제 요청 중에 쿠폰이 중지되거나 기간이 끝나도 이미 할인된 금액으로 결제됐으니 사용 처리한다
 * 사용 처리는 상태가 아직 RESERVED인 행만 바꾸는 조건부 UPDATE라, 해제(취소·만료)가 먼저 처리한 기록은 USED로 덮어쓰지 않는다.
 * (결제 중(PAYING) 주문은 취소·만료 대상이 아니므로 결제 중에 예약이 해제되는 일은 없다고 본다)
 */
@Component
@RequiredArgsConstructor
public class CouponUseReservedCouponsUseCase {

    private final CouponUsageRepository couponUsageRepository;

    public void validateUsable(Long orderId, LocalDateTime now) {
        if (couponUsageRepository.countReservedWithUnusableCoupon(orderId, now) > 0) {
            throw new DomainException(CouponErrorCode.COUPON_NOT_ACTIVE);
        }
    }

    public int use(Long orderId, LocalDateTime now) {
        return couponUsageRepository.markUsedIfReserved(orderId, now);
    }
}
