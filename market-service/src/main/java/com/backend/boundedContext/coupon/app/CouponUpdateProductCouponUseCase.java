package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.app.dto.ProductCouponUpdateCommand;
import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.CouponDisplayStatus;
import com.backend.boundedContext.coupon.domain.CouponErrorCode;
import com.backend.boundedContext.coupon.domain.ProductCoupon;
import com.backend.boundedContext.coupon.domain.ProductCouponDiscount;
import com.backend.boundedContext.coupon.out.CouponRepository;
import com.backend.boundedContext.coupon.out.CouponSellerPort;
import com.backend.boundedContext.coupon.out.ProductCouponRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 상품 쿠폰 수정 (판매자). 보낸 항목만 바꾼다.
 *
 * <ul>
 *     <li>시작 전(SCHEDULED): 쿠폰명, 할인, 기간, 수량을 바꿀 수 있다.</li>
 *     <li>시작 후(ACTIVE): 종료 일시 연장(validUntil)만 된다. 다른 항목을 보내면 409-COUPON_NOT_EDITABLE.</li>
 *     <li>끝났거나 중지된 쿠폰은 409-COUPON_NOT_EDITABLE.</li>
 *     <li>바뀐 기간이 같은 인플루언서와 상품의 다른 운영 중 쿠폰과 겹치면 409-COUPON_PERIOD_OVERLAPPED.</li>
 * </ul>
 *
 * <p>잠금 순서는 협약 → 쿠폰이다. 협약 종료가 협약을 잠근 뒤 쿠폰을 중지하므로 순서를 맞춰 데드락을 피하고,
 * 협약을 잠근 상태라 기간 겹침 확인도 다른 발행, 수정과 겹치지 않는다.
 * 없는 쿠폰이거나 상품 쿠폰이 아니면 404-COUPON_NOT_FOUND, 내 쿠폰이 아니면 403-FORBIDDEN.
 */
@Component
@RequiredArgsConstructor
public class CouponUpdateProductCouponUseCase {

    private final CouponSupport couponSupport;
    private final CouponSellerPort couponSellerPort;
    private final CouponRepository couponRepository;
    private final ProductCouponRepository productCouponRepository;

    public void update(Long memberId, Long couponId, ProductCouponUpdateCommand command) {
        Long sellerId = couponSellerPort.getApprovedSellerId(memberId);

        Long partnershipId = productCouponRepository.findPartnershipIdByCouponId(couponId)
                .orElseThrow(() -> new DomainException(CouponErrorCode.COUPON_NOT_FOUND));
        couponSupport.lockPartnership(partnershipId);
        Coupon coupon = couponRepository.findByIdForUpdate(couponId)
                .orElseThrow(() -> new DomainException(CouponErrorCode.COUPON_NOT_FOUND));
        if (!coupon.isIssuedBySeller(sellerId)) {
            throw new DomainException(GlobalErrorCode.FORBIDDEN);
        }
        ProductCoupon productCoupon = productCouponRepository.findWithCouponByCouponId(couponId)
                .orElseThrow(() -> new DomainException(CouponErrorCode.COUPON_NOT_FOUND));

        // 시작 전·후 판단은 잠금을 잡은 뒤의 시각으로 한다. 잠금을 기다리는 사이 시작 시각이 지나면
        // 이미 예약이 들어왔을 수 있으므로 시작 전 수정(남은 수량을 총 수량으로 다시 맞춤)을 하면 안 된다
        LocalDateTime now = LocalDateTime.now();
        if (coupon.displayStatus(now) == CouponDisplayStatus.SCHEDULED) {
            updateBeforeStart(coupon, productCoupon, command, now);
        } else {
            extendAfterStart(coupon, productCoupon, command, now);
        }
    }

    private void updateBeforeStart(Coupon coupon, ProductCoupon productCoupon, ProductCouponUpdateCommand command,
                                   LocalDateTime now) {
        String name = command.name() != null ? command.name() : coupon.getName();
        LocalDateTime validFrom = command.validFrom() != null ? command.validFrom() : coupon.getValidFrom();
        LocalDateTime validUntil = command.validUntil() != null ? command.validUntil() : coupon.getValidUntil();

        boolean quantityLimited = command.quantityLimited() != null ? command.quantityLimited() : coupon.isQuantityLimited();
        // 수량 제한 여부나 총 수량 중 하나라도 보냈으면 보낸 총 수량을 쓴다(제한 없음이면 null)
        Integer totalQuantity = (command.quantityLimited() != null || command.totalQuantity() != null)
                ? command.totalQuantity()
                : coupon.getTotalQuantity();

        ProductCouponDiscount discount = mergeDiscount(productCoupon.discount(), command);

        // 기간, 수량은 Coupon이, 할인은 ProductCoupon이 검증한다. 겹침 확인은 검증된 기간으로 한다
        coupon.updateBeforeStart(name, quantityLimited, totalQuantity, validFrom, validUntil, now);
        productCoupon.changeDiscount(discount);
        couponSupport.checkNotOverlapped(productCoupon.getInfluencerId(), productCoupon.getProductId(),
                validFrom, validUntil, coupon.getId());
    }

    // 할인 방식을 바꾸면 할인값과 최대 할인액도 새로 보낸 값을 쓴다(최대 할인액은 보내지 않으면 null)
    private ProductCouponDiscount mergeDiscount(ProductCouponDiscount current, ProductCouponUpdateCommand command) {
        if (command.discountType() != null) {
            return new ProductCouponDiscount(command.discountType(),
                    command.discountValue() != null ? command.discountValue() : current.discountValue(),
                    command.maxDiscountAmount());
        }
        return new ProductCouponDiscount(current.discountType(),
                command.discountValue() != null ? command.discountValue() : current.discountValue(),
                command.maxDiscountAmount() != null ? command.maxDiscountAmount() : current.maxDiscountAmount());
    }

    private void extendAfterStart(Coupon coupon, ProductCoupon productCoupon, ProductCouponUpdateCommand command,
                                  LocalDateTime now) {
        if (command.hasFieldsOtherThanValidUntil() || command.validUntil() == null) {
            throw new DomainException(CouponErrorCode.COUPON_NOT_EDITABLE);
        }

        coupon.extendValidUntil(command.validUntil(), now);
        couponSupport.checkNotOverlapped(productCoupon.getInfluencerId(), productCoupon.getProductId(),
                coupon.getValidFrom(), coupon.getValidUntil(), coupon.getId());
    }
}
