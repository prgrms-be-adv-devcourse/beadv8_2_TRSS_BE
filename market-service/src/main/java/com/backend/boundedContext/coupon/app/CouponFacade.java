package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.app.dto.AdminCouponDetailDto;
import com.backend.boundedContext.coupon.app.dto.AdminCouponSummaryDto;
import com.backend.boundedContext.coupon.app.dto.CartCouponCreateCommand;
import com.backend.boundedContext.coupon.app.dto.CartCouponsDto;
import com.backend.boundedContext.coupon.app.dto.MyCouponUsageDto;
import com.backend.boundedContext.coupon.app.dto.PartnershipDto;
import com.backend.boundedContext.coupon.app.dto.PickedProductDto;
import com.backend.boundedContext.coupon.app.dto.ProductCouponDto;
import com.backend.boundedContext.coupon.app.dto.ProductCouponIssueCommand;
import com.backend.boundedContext.coupon.app.dto.ProductCouponIssueResultDto;
import com.backend.boundedContext.coupon.app.dto.ProductCouponUpdateCommand;
import com.backend.boundedContext.coupon.app.dto.ProductCouponValidationDto;
import com.backend.boundedContext.coupon.domain.CouponDisplayStatus;
import com.backend.boundedContext.coupon.domain.CouponType;
import com.backend.boundedContext.coupon.domain.PartnershipStatus;
import com.backend.global.page.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
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
    private final CouponReserveProductCouponUseCase couponReserveProductCouponUseCase;
    private final PartnershipRequestUseCase partnershipRequestUseCase;
    private final PartnershipChangeStatusUseCase partnershipChangeStatusUseCase;
    private final PartnershipGetPartnershipsUseCase partnershipGetPartnershipsUseCase;
    private final PartnershipGetPickedProductsUseCase partnershipGetPickedProductsUseCase;
    private final CouponIssueProductCouponUseCase couponIssueProductCouponUseCase;
    private final CouponUpdateProductCouponUseCase couponUpdateProductCouponUseCase;
    private final CouponStopBySellerUseCase couponStopBySellerUseCase;
    private final CouponGetProductCouponsUseCase couponGetProductCouponsUseCase;
    private final CouponValidateProductCouponCodeUseCase couponValidateProductCouponCodeUseCase;
    private final CouponStopProductCouponsByProductsUseCase couponStopProductCouponsByProductsUseCase;

    // 장바구니 쿠폰 등록 (관리자)
    @Transactional
    public Long createCartCoupon(Long adminId, CartCouponCreateCommand command) {
        return couponCreateCartCouponUseCase.create(adminId, command);
    }

    // 전체 쿠폰 목록 (관리자)
    @Transactional(readOnly = true)
    public PageResponse<AdminCouponSummaryDto> getCouponsForAdmin(CouponType type, CouponDisplayStatus status,
                                                                  Long sellerId, Long influencerId, Pageable pageable) {
        return couponGetAdminCouponsUseCase.getCoupons(type, status, sellerId, influencerId, pageable);
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

    // 장바구니 쿠폰 예약 → 할인액 (주문 생성)
    // 상품 쿠폰 예약 → 할인액 (주문 생성)
    @Transactional
    public long reserveProductCoupon(Long memberId, String code, Long orderId, Long orderItemId, Long productId,
                                     long lineAmount, LocalDateTime now) {
        return couponReserveProductCouponUseCase.reserve(memberId, code, orderId, orderItemId, productId, lineAmount, now);
    }

    @Transactional
    public long reserveCartCoupon(Long memberId, Long couponId, Long orderId, long orderAmount, LocalDateTime now) {
        return couponReserveCartCouponUseCase.reserve(memberId, couponId, orderId, orderAmount, now);
    }

    // 주문의 예약 쿠폰 사용 처리 (결제 성공). 예약 뒤 중지됐거나 기간이 끝난 쿠폰이 있으면 409-COUPON_NOT_ACTIVE로 결제와 함께 롤백
    @Transactional
    public int useReservedCoupons(Long orderId, LocalDateTime now) {
        return couponUseReservedCouponsUseCase.use(orderId, now);
    }

    // 주문의 예약 쿠폰 해제와 수량 복원 (결제 전 취소, 만료)
    @Transactional
    public int releaseReservedCoupons(Long orderId, LocalDateTime now) {
        return couponReleaseReservedCouponsUseCase.release(orderId, now);
    }

    // ===== 협약 =====

    // 협약 요청 (판매자) → 협약 ID
    @Transactional
    public Long requestPartnership(Long memberId, Long influencerId, Integer commissionRate, String message) {
        return partnershipRequestUseCase.request(memberId, influencerId, commissionRate, message);
    }

    // 내 협약 목록 (판매자)
    @Transactional(readOnly = true)
    public PageResponse<PartnershipDto> getSellerPartnerships(Long memberId, PartnershipStatus status, Pageable pageable) {
        return partnershipGetPartnershipsUseCase.getForSeller(memberId, status, pageable);
    }

    // 협약 인플루언서가 pick한 내 판매 중 상품 (판매자, 상품 쿠폰 발행 대상)
    @Transactional(readOnly = true)
    public List<PickedProductDto> getPickedProducts(Long memberId, Long partnershipId) {
        return partnershipGetPickedProductsUseCase.getPickedProducts(memberId, partnershipId);
    }

    // 협약 종료 (판매자) → 그 협약의 쿠폰 중지
    @Transactional
    public void endPartnershipBySeller(Long memberId, Long partnershipId) {
        partnershipChangeStatusUseCase.endBySeller(memberId, partnershipId);
    }

    // 받은 협약 목록 (인플루언서)
    @Transactional(readOnly = true)
    public PageResponse<PartnershipDto> getInfluencerPartnerships(Long memberId, PartnershipStatus status, Pageable pageable) {
        return partnershipGetPartnershipsUseCase.getForInfluencer(memberId, status, pageable);
    }

    // 협약 수락 (인플루언서)
    @Transactional
    public void acceptPartnership(Long memberId, Long partnershipId) {
        partnershipChangeStatusUseCase.accept(memberId, partnershipId);
    }

    // 협약 거절 (인플루언서)
    @Transactional
    public void rejectPartnership(Long memberId, Long partnershipId) {
        partnershipChangeStatusUseCase.reject(memberId, partnershipId);
    }

    // 협약 종료 (인플루언서) → 그 협약의 쿠폰 중지
    @Transactional
    public void endPartnershipByInfluencer(Long memberId, Long partnershipId) {
        partnershipChangeStatusUseCase.endByInfluencer(memberId, partnershipId);
    }

    // 전체 협약 목록 (관리자)
    @Transactional(readOnly = true)
    public PageResponse<PartnershipDto> getPartnershipsForAdmin(Long sellerId, Long influencerId, PartnershipStatus status,
                                                                Pageable pageable) {
        return partnershipGetPartnershipsUseCase.getForAdmin(sellerId, influencerId, status, pageable);
    }

    // 판매자 제재 → 그 판매자의 협약 모두 종료, 쿠폰 중지 (판매자 이벤트 컨슈머)
    @Transactional
    public int endPartnershipsBySellerSanction(Long sellerId) {
        return partnershipChangeStatusUseCase.endAllBySeller(sellerId);
    }

    // ===== 상품 쿠폰 =====

    // 상품 쿠폰 발행 (판매자) → 쿠폰 ID와 코드
    @Transactional
    public ProductCouponIssueResultDto issueProductCoupon(Long memberId, ProductCouponIssueCommand command) {
        return couponIssueProductCouponUseCase.issue(memberId, command);
    }

    // 내 상품 쿠폰 목록 (판매자)
    @Transactional(readOnly = true)
    public PageResponse<ProductCouponDto> getSellerCoupons(Long memberId, CouponDisplayStatus status, Pageable pageable) {
        return couponGetProductCouponsUseCase.getForSeller(memberId, status, pageable);
    }

    // 상품 쿠폰 상세 (판매자)
    @Transactional(readOnly = true)
    public ProductCouponDto getSellerCoupon(Long memberId, Long couponId) {
        return couponGetProductCouponsUseCase.getDetailForSeller(memberId, couponId);
    }

    // 상품 쿠폰 수정 (판매자)
    @Transactional
    public void updateProductCoupon(Long memberId, Long couponId, ProductCouponUpdateCommand command) {
        couponUpdateProductCouponUseCase.update(memberId, couponId, command);
    }

    // 상품 쿠폰 중지 (판매자)
    @Transactional
    public void stopCouponBySeller(Long memberId, Long couponId) {
        couponStopBySellerUseCase.stop(memberId, couponId);
    }

    // 나에게 발행된 쿠폰 목록 (인플루언서)
    @Transactional(readOnly = true)
    public PageResponse<ProductCouponDto> getInfluencerCoupons(Long memberId, CouponDisplayStatus status, Pageable pageable) {
        return couponGetProductCouponsUseCase.getForInfluencer(memberId, status, pageable);
    }

    // 상품 쿠폰 코드 검증 (구매자, 예약하지 않음)
    @Transactional(readOnly = true)
    public ProductCouponValidationDto validateProductCouponCode(Long memberId, String code, Long cartItemId) {
        return couponValidateProductCouponCodeUseCase.validate(memberId, code, cartItemId);
    }

    // 판매를 멈춘 상품의 상품 쿠폰 자동 중지 (CouponApi, 상품 컨텍스트 트랜잭션에 참여)
    @Transactional
    public int stopProductCouponsByProducts(Collection<Long> productIds, String reason) {
        return couponStopProductCouponsByProductsUseCase.stop(productIds, reason);
    }
}
