package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.app.dto.PickedProductDto;
import com.backend.boundedContext.coupon.domain.CouponErrorCode;
import com.backend.boundedContext.coupon.domain.Partnership;
import com.backend.boundedContext.coupon.out.CouponProductPort;
import com.backend.boundedContext.coupon.out.CouponSellerPort;
import com.backend.boundedContext.coupon.out.InfluencerPickPort;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 협약 인플루언서가 pick한 내 판매 중 상품 목록 (상품 쿠폰 발행 대상, 상품 ID 최신순)
 * 내 협약이 아니면 403-FORBIDDEN, 진행 중인 협약이 아니면 409-PARTNERSHIP_NOT_ACTIVE.
 */
@Component
@RequiredArgsConstructor
public class PartnershipGetPickedProductsUseCase {

    private final CouponSupport couponSupport;
    private final CouponSellerPort couponSellerPort;
    private final InfluencerPickPort influencerPickPort;
    private final CouponProductPort couponProductPort;

    public List<PickedProductDto> getPickedProducts(Long memberId, Long partnershipId) {
        Long sellerId = couponSellerPort.getApprovedSellerId(memberId);
        Partnership partnership = couponSupport.getPartnership(partnershipId);
        if (!partnership.isOwnedBySeller(sellerId)) {
            throw new DomainException(GlobalErrorCode.FORBIDDEN);
        }
        if (!partnership.isActive()) {
            throw new DomainException(CouponErrorCode.PARTNERSHIP_NOT_ACTIVE);
        }

        List<Long> pickedProductIds = influencerPickPort.findPickedProductIds(partnership.getInfluencerId());
        if (pickedProductIds.isEmpty()) {
            return List.of();
        }

        return couponProductPort.getProducts(pickedProductIds).stream()
                .filter(product -> sellerId.equals(product.sellerId()) && product.onSale())
                .sorted((a, b) -> Long.compare(b.productId(), a.productId()))
                .map(PickedProductDto::from)
                .toList();
    }
}
