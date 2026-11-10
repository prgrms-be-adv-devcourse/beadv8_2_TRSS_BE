package com.backend.boundedContext.coupon.domain;

import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품 쿠폰의 상품 쿠폰 전용 정보 (쿠폰 1장 = 판매자 1명 × 인플루언서 1명 × 상품 1개)
 *
 * <p>공통 정보(쿠폰명, 기간, 수량, 상태)는 {@link Coupon}에 있고, 이 엔티티가 쿠폰을 한쪽으로만 참조한다(coupon_id UNIQUE).
 * 쿠폰마다 코드가 하나 생기고, 그 상품에만 할인이 적용된다.
 */
@Entity
@Table(name = "product_coupon",
        indexes = {
                @Index(name = "idx_product_coupon_influencer_product", columnList = "influencer_id, product_id"),
                @Index(name = "idx_product_coupon_product", columnList = "product_id"),
                @Index(name = "idx_product_coupon_partnership", columnList = "partnership_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductCoupon extends BaseIdAndTime {

    private static final int MAX_RATE = 100;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coupon_id", nullable = false, unique = true)
    private Coupon coupon;

    @Column(nullable = false)
    private Long partnershipId;

    // 인플루언서 ID(influencer.id)
    @Column(nullable = false)
    private Long influencerId;

    @Column(nullable = false)
    private Long productId;

    // 대문자와 숫자 10자리 (CouponCodeGenerator)
    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DiscountType discountType;

    // FIXED면 할인액(원), RATE면 할인율(%)
    @Column(nullable = false)
    private long discountValue;

    // RATE일 때만 있음
    private Long maxDiscountAmount;

    public static ProductCoupon create(Coupon coupon, Long partnershipId, Long influencerId, Long productId,
                                       String code, ProductCouponDiscount discount) {
        validateDiscount(discount);

        ProductCoupon productCoupon = new ProductCoupon();
        productCoupon.coupon = coupon;
        productCoupon.partnershipId = partnershipId;
        productCoupon.influencerId = influencerId;
        productCoupon.productId = productId;
        productCoupon.code = code;
        productCoupon.applyDiscount(discount);
        return productCoupon;
    }

    // 할인 내용 변경 (쿠폰 시작 전에만. 시작 여부는 호출하는 쪽이 Coupon.updateBeforeStart로 먼저 확인한다)
    public void changeDiscount(ProductCouponDiscount discount) {
        validateDiscount(discount);
        applyDiscount(discount);
    }

    private void applyDiscount(ProductCouponDiscount discount) {
        this.discountType = discount.discountType();
        this.discountValue = discount.discountValue();
        this.maxDiscountAmount = discount.maxDiscountAmount();
    }

    // FIXED: 할인액 1원 이상, 최대 할인액 없음 / RATE: 1~100%, 최대 할인액 1원 이상 필수. 요청 DTO에서 먼저 400으로 거른다
    private static void validateDiscount(ProductCouponDiscount discount) {
        boolean valid = switch (discount.discountType()) {
            case FIXED -> discount.discountValue() >= 1 && discount.maxDiscountAmount() == null;
            case RATE -> discount.discountValue() >= 1 && discount.discountValue() <= MAX_RATE
                    && discount.maxDiscountAmount() != null && discount.maxDiscountAmount() >= 1;
        };

        if (!valid) {
            throw new DomainException(GlobalErrorCode.VALIDATION_FAILED);
        }
    }

    public ProductCouponDiscount discount() {
        return new ProductCouponDiscount(discountType, discountValue, maxDiscountAmount);
    }

    /**
     * 주문 항목 금액(단가 × 수량)에 대한 할인액. 항목 금액을 넘지 않는다.
     * 정률은 원 단위 버림 후 최대 할인액으로 자른다.
     */
    public long discountFor(long lineAmount) {
        if (lineAmount <= 0) return 0;

        long discount = switch (discountType) {
            case FIXED -> discountValue;
            case RATE -> Math.min(Math.multiplyExact(lineAmount, discountValue) / MAX_RATE, maxDiscountAmount);
        };
        return Math.min(discount, lineAmount);
    }
}
