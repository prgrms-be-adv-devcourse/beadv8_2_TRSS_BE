package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.app.dto.PartnershipDto;
import com.backend.boundedContext.coupon.app.dto.ProductCouponDto;
import com.backend.boundedContext.coupon.app.dto.ProductCouponInfoDto;
import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.CouponErrorCode;
import com.backend.boundedContext.coupon.domain.CouponStatus;
import com.backend.boundedContext.coupon.domain.CouponUsageStatus;
import com.backend.boundedContext.coupon.domain.Partnership;
import com.backend.boundedContext.coupon.domain.ProductCoupon;
import com.backend.boundedContext.coupon.out.CouponInfluencerPort;
import com.backend.boundedContext.coupon.out.CouponProductPort;
import com.backend.boundedContext.coupon.out.CouponRepository;
import com.backend.boundedContext.coupon.out.CouponSellerPort;
import com.backend.boundedContext.coupon.out.CouponUsageRepository;
import com.backend.boundedContext.coupon.out.CouponUsageStat;
import com.backend.boundedContext.coupon.out.PartnershipRepository;
import com.backend.boundedContext.coupon.out.ProductCouponRepository;
import com.backend.shared.product.dto.ProductSnapshotDto;
import com.backend.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
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
    private final ProductCouponRepository productCouponRepository;
    private final PartnershipRepository partnershipRepository;
    private final CouponSellerPort couponSellerPort;
    private final CouponInfluencerPort couponInfluencerPort;
    private final CouponProductPort couponProductPort;

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

    // 쿠폰 ID → 상품 쿠폰 정보 (관리자 목록, 상세). 장바구니 쿠폰은 결과에 없다
    public Map<Long, ProductCouponInfoDto> getProductCouponInfos(Collection<Long> couponIds) {
        if (couponIds.isEmpty()) {
            return Map.of();   // 빈 IN 절 방지
        }

        return productCouponRepository.findAllByCouponIdIn(couponIds).stream()
                .collect(Collectors.toMap(pc -> pc.getCoupon().getId(), ProductCouponInfoDto::from));
    }

    /**
     * 상품 쿠폰 목록을 화면용으로 바꾼다. 사용 집계, 상호명, 활동명, 상품명은 각각 한 번에 모아서 조회한다.
     * 쿠폰(pc.coupon)은 미리 함께 읽어 둔 상태여야 한다.
     */
    public List<ProductCouponDto> toProductCouponDtos(List<ProductCoupon> productCoupons, LocalDateTime now) {
        if (productCoupons.isEmpty()) {
            return List.of();
        }

        Map<Long, CouponUsageStat> usedStats = getUsedStats(
                productCoupons.stream().map(pc -> pc.getCoupon().getId()).toList());
        Map<Long, String> businessNames = couponSellerPort.getBusinessNames(
                productCoupons.stream().map(pc -> pc.getCoupon().getIssuerId()).collect(Collectors.toSet()));
        Map<Long, String> activityNames = couponInfluencerPort.getActivityNames(
                productCoupons.stream().map(ProductCoupon::getInfluencerId).collect(Collectors.toSet()));
        Map<Long, String> productNames = couponProductPort.getProducts(
                        productCoupons.stream().map(ProductCoupon::getProductId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(ProductSnapshotDto::productId, ProductSnapshotDto::name));

        return productCoupons.stream()
                .map(pc -> {
                    Long couponId = pc.getCoupon().getId();
                    return ProductCouponDto.of(pc, now,
                            usedStats.getOrDefault(couponId, CouponUsageStat.empty(couponId)),
                            businessNames.get(pc.getCoupon().getIssuerId()),
                            activityNames.get(pc.getInfluencerId()),
                            productNames.get(pc.getProductId()));
                })
                .toList();
    }

    // 협약 조회. 없으면 404-PARTNERSHIP_NOT_FOUND
    public Partnership getPartnership(Long partnershipId) {
        return partnershipRepository.findById(partnershipId)
                .orElseThrow(() -> new DomainException(CouponErrorCode.PARTNERSHIP_NOT_FOUND));
    }

    // 협약을 쓰기 잠금해 조회. 없으면 404-PARTNERSHIP_NOT_FOUND
    public Partnership lockPartnership(Long partnershipId) {
        return partnershipRepository.findByIdForUpdate(partnershipId)
                .orElseThrow(() -> new DomainException(CouponErrorCode.PARTNERSHIP_NOT_FOUND));
    }

    // 협약 목록을 화면용으로 바꾼다. 상호명, 활동명은 한 번에 모아서 조회한다
    public List<PartnershipDto> toPartnershipDtos(List<Partnership> partnerships) {
        if (partnerships.isEmpty()) {
            return List.of();
        }

        Map<Long, String> businessNames = couponSellerPort.getBusinessNames(
                partnerships.stream().map(Partnership::getSellerId).collect(Collectors.toSet()));
        Map<Long, String> activityNames = couponInfluencerPort.getActivityNames(
                partnerships.stream().map(Partnership::getInfluencerId).collect(Collectors.toSet()));

        return partnerships.stream()
                .map(p -> PartnershipDto.of(p, businessNames.get(p.getSellerId()), activityNames.get(p.getInfluencerId())))
                .toList();
    }

    /**
     * 같은 인플루언서, 같은 상품으로 기간이 겹치는 운영 중 쿠폰이 있으면 409-COUPON_PERIOD_OVERLAPPED.
     * 중지된 쿠폰은 보지 않는다. excludeCouponId는 수정할 때 자기 자신을 빼려고 쓴다(발행이면 null).
     * 협약을 잠근 상태에서 불러야 동시에 발행되는 쿠폰과 겹치지 않는다.
     */
    public void checkNotOverlapped(Long influencerId, Long productId, LocalDateTime validFrom, LocalDateTime validUntil,
                                   Long excludeCouponId) {
        long overlapped = productCouponRepository.countOverlapping(influencerId, productId, validFrom, validUntil,
                excludeCouponId == null ? -1L : excludeCouponId, CouponStatus.ACTIVE);
        if (overlapped > 0) {
            throw new DomainException(CouponErrorCode.COUPON_PERIOD_OVERLAPPED);
        }
    }
}
