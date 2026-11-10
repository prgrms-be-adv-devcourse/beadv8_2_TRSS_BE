package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.app.dto.AdminCouponDetailDto;
import com.backend.boundedContext.coupon.app.dto.AdminCouponSummaryDto;
import com.backend.boundedContext.coupon.app.dto.CartCouponCreateCommand;
import com.backend.boundedContext.coupon.app.dto.CartCouponsDto;
import com.backend.boundedContext.coupon.app.dto.MyCouponUsageDto;
import com.backend.boundedContext.coupon.domain.CouponDisplayStatus;
import com.backend.boundedContext.coupon.domain.CouponType;
import com.backend.global.page.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 쿠폰 컨텍스트 진입점
 */
@Service
@RequiredArgsConstructor
public class CouponFacade {

    private final CouponCreateCartCouponUseCase couponCreateCartCouponUseCase;
    private final CouponGetAdminCouponsUseCase couponGetAdminCouponsUseCase;
    private final CouponGetAdminCouponDetailUseCase couponGetAdminCouponDetailUseCase;
    private final CouponStopByAdminUseCase couponStopByAdminUseCase;
    private final CouponGetCartCouponsUseCase couponGetCartCouponsUseCase;
    private final CouponGetMyUsagesUseCase couponGetMyUsagesUseCase;
    private final CouponReserveCartCouponUseCase couponReserveCartCouponUseCase;
    private final CouponUseReservedCouponsUseCase couponUseReservedCouponsUseCase;
    private final CouponReleaseReservedCouponsUseCase couponReleaseReservedCouponsUseCase;

    // 장바구니 쿠폰 등록 (관리자)
    @Transactional
    public Long createCartCoupon(Long adminId, CartCouponCreateCommand command) {
        return couponCreateCartCouponUseCase.create(adminId, command);
    }

    // 전체 쿠폰 목록 (관리자)
    @Transactional(readOnly = true)
    public PageResponse<AdminCouponSummaryDto> getCouponsForAdmin(CouponType type, CouponDisplayStatus status, Pageable pageable) {
        return couponGetAdminCouponsUseCase.getCoupons(type, status, pageable);
    }

    // 쿠폰 상세 (관리자)
    @Transactional(readOnly = true)
    public AdminCouponDetailDto getCouponForAdmin(Long couponId, Pageable usagePageable) {
        return couponGetAdminCouponDetailUseCase.getCoupon(couponId, usagePageable);
    }

    // 쿠폰 강제 중지 (관리자)
    @Transactional
    public void stopCouponByAdmin(Long couponId, String reason) {
        couponStopByAdminUseCase.stop(couponId, reason);
    }

    // 사용 가능한 장바구니 쿠폰 목록 (구매자)
    @Transactional(readOnly = true)
    public CartCouponsDto getCartCoupons(Long memberId, List<Long> cartItemIds) {
        return couponGetCartCouponsUseCase.getCartCoupons(memberId, cartItemIds);
    }

    // 내 쿠폰 사용 내역 (구매자)
    @Transactional(readOnly = true)
    public PageResponse<MyCouponUsageDto> getMyCouponUsages(Long memberId, Pageable pageable) {
        return couponGetMyUsagesUseCase.getUsages(memberId, pageable);
    }

    // 장바구니 쿠폰 예약 → 할인액 (주문 생성, 다음 이슈에서 연결)
    @Transactional
    public long reserveCartCoupon(Long memberId, Long couponId, Long orderId, long orderAmount, LocalDateTime now) {
        return couponReserveCartCouponUseCase.reserve(memberId, couponId, orderId, orderAmount, now);
    }

    // 주문의 예약 쿠폰 사용 처리 (결제 성공, 다음 이슈에서 연결). 예약 뒤 중지됐거나 기간이 끝난 쿠폰이 있으면 409-COUPON_NOT_ACTIVE로 결제와 함께 롤백
    @Transactional
    public int useReservedCoupons(Long orderId, LocalDateTime now) {
        return couponUseReservedCouponsUseCase.use(orderId, now);
    }

    // 주문의 예약 쿠폰 해제와 수량 복원 (결제 전 취소, 만료. 다음 이슈에서 연결)
    @Transactional
    public int releaseReservedCoupons(Long orderId, LocalDateTime now) {
        return couponReleaseReservedCouponsUseCase.release(orderId, now);
    }
}
