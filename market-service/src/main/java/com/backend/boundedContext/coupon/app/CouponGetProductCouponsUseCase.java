package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.app.dto.ProductCouponDto;
import com.backend.boundedContext.coupon.domain.CouponDisplayStatus;
import com.backend.boundedContext.coupon.domain.CouponErrorCode;
import com.backend.boundedContext.coupon.domain.ProductCoupon;
import com.backend.boundedContext.coupon.out.CouponInfluencerPort;
import com.backend.boundedContext.coupon.out.CouponSellerPort;
import com.backend.boundedContext.coupon.out.ProductCouponRepository;
import com.backend.boundedContext.coupon.out.ProductCouponSpecifications;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.global.page.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 상품 쿠폰 조회 (판매자 내 쿠폰 목록, 상세와 인플루언서 나에게 발행된 쿠폰 목록). 목록은 최신 발행순이다
 */
@Component
@RequiredArgsConstructor
public class CouponGetProductCouponsUseCase {

    private final CouponSupport couponSupport;
    private final CouponSellerPort couponSellerPort;
    private final CouponInfluencerPort couponInfluencerPort;
    private final ProductCouponRepository productCouponRepository;

    public PageResponse<ProductCouponDto> getForSeller(Long memberId, CouponDisplayStatus status, Pageable pageable) {
        Long sellerId = couponSellerPort.getApprovedSellerId(memberId);
        return search(sellerId, null, status, pageable);
    }

    public PageResponse<ProductCouponDto> getForInfluencer(Long memberId, CouponDisplayStatus status, Pageable pageable) {
        Long influencerId = couponInfluencerPort.getApprovedInfluencerIdByMemberId(memberId);
        return search(null, influencerId, status, pageable);
    }

    // 판매자 쿠폰 상세. 없는 쿠폰이거나 상품 쿠폰이 아니면 404-COUPON_NOT_FOUND, 내 쿠폰이 아니면 403-FORBIDDEN
    public ProductCouponDto getDetailForSeller(Long memberId, Long couponId) {
        Long sellerId = couponSellerPort.getApprovedSellerId(memberId);
        ProductCoupon productCoupon = productCouponRepository.findWithCouponByCouponId(couponId)
                .orElseThrow(() -> new DomainException(CouponErrorCode.COUPON_NOT_FOUND));
        if (!productCoupon.getCoupon().isIssuedBySeller(sellerId)) {
            throw new DomainException(GlobalErrorCode.FORBIDDEN);
        }

        return couponSupport.toProductCouponDtos(List.of(productCoupon), LocalDateTime.now()).getFirst();
    }

    private PageResponse<ProductCouponDto> search(Long sellerId, Long influencerId, CouponDisplayStatus status, Pageable pageable) {
        LocalDateTime now = LocalDateTime.now();
        // 요청의 sort는 무시하고 최신 발행순으로만 준다
        Pageable latestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "id"));

        Page<ProductCoupon> productCoupons = productCouponRepository.findAll(
                ProductCouponSpecifications.search(sellerId, influencerId, status, now), latestFirst);

        Map<Long, ProductCouponDto> dtos = couponSupport.toProductCouponDtos(productCoupons.getContent(), now).stream()
                .collect(Collectors.toMap(ProductCouponDto::couponId, Function.identity()));
        return PageResponse.from(productCoupons, pc -> dtos.get(pc.getCoupon().getId()));
    }
}
