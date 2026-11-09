package com.backend.boundedContext.coupon.domain;

import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 쿠폰 (상품 쿠폰, 장바구니 쿠폰 공통)
 *
 * <p>상태는 운영 상태(ACTIVE, STOPPED)만 저장하고, 시작 전과 종료는 유효 기간으로 계산한다({@link #displayStatus}).
 *
 * <p>남은 수량은 엔티티로 바꾸지 않고 조건부 UPDATE(CouponRepository)로만 줄이고 늘린다.
 * 그래서 {@code @DynamicUpdate}로 바뀐 컬럼만 UPDATE 하게 해서, 중지처럼 엔티티를 고칠 때
 * 먼저 읽어 둔 남은 수량이 다른 주문의 차감을 덮어쓰지 않게 한다.
 */
@Entity
@Table(name = "coupon",
        indexes = @Index(name = "idx_coupon_type_status_valid", columnList = "coupon_type, status, valid_until"))
@DynamicUpdate
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Coupon extends BaseIdAndTime {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CouponType couponType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CouponIssuerType issuerType;

    // SELLER면 판매자 ID(seller.id), ADMIN이면 관리자 회원 ID
    @Column(nullable = false)
    private Long issuerId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private boolean quantityLimited;

    // 수량 제한이 없으면 null
    private Integer totalQuantity;

    // 수량 제한이 없으면 null. 조건부 UPDATE로만 바뀐다
    private Integer remainingQuantity;

    // 시작 시각 (포함)
    @Column(nullable = false)
    private LocalDateTime validFrom;

    // 종료 시각 (포함하지 않음)
    @Column(nullable = false)
    private LocalDateTime validUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CouponStatus status;

    private LocalDateTime stoppedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CouponStoppedBy stoppedBy;

    @Column(length = 300)
    private String stopReason;

    // 장바구니 쿠폰의 할인 구간 (최소 주문 금액 오름차순). 상품 쿠폰은 비어 있다
    @OneToMany(mappedBy = "coupon", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("minOrderAmount asc")
    @BatchSize(size = 100)
    private List<CartCouponTier> tiers = new ArrayList<>();

    /**
     * 장바구니 쿠폰 등록 (관리자)
     * 구간은 순서와 상관없이 받아 최소 주문 금액 순으로 정렬해 저장한다.
     */
    public static Coupon createCartCoupon(Long adminId, String name, List<CartCouponTierSpec> tierSpecs,
                                          boolean quantityLimited, Integer totalQuantity,
                                          LocalDateTime validFrom, LocalDateTime validUntil, LocalDateTime now) {
        validatePeriod(validFrom, validUntil, now);
        validateQuantity(quantityLimited, totalQuantity);
        List<CartCouponTierSpec> sortedTiers = validateTiers(tierSpecs);

        Coupon coupon = new Coupon();
        coupon.couponType = CouponType.CART;
        coupon.issuerType = CouponIssuerType.ADMIN;
        coupon.issuerId = adminId;
        coupon.name = name;
        coupon.quantityLimited = quantityLimited;
        coupon.totalQuantity = totalQuantity;
        coupon.remainingQuantity = totalQuantity;
        coupon.validFrom = validFrom;
        coupon.validUntil = validUntil;
        coupon.status = CouponStatus.ACTIVE;

        sortedTiers.forEach(spec -> coupon.tiers.add(CartCouponTier.create(coupon, spec)));
        return coupon;
    }

    // 시작 < 종료, 종료 > 현재. 시작은 현재보다 이전이어도 된다(등록 즉시 사용 가능)
    private static void validatePeriod(LocalDateTime validFrom, LocalDateTime validUntil, LocalDateTime now) {
        if (validFrom == null || validUntil == null
                || !validFrom.isBefore(validUntil)
                || !validUntil.isAfter(now)) {
            throw new DomainException(CouponErrorCode.COUPON_PERIOD_INVALID);
        }
    }

    // 수량 제한이면 총 수량 1 이상, 제한이 없으면 총 수량을 받지 않는다 (요청 DTO에서 먼저 400으로 거른다)
    private static void validateQuantity(boolean quantityLimited, Integer totalQuantity) {
        boolean valid = quantityLimited
                ? totalQuantity != null && totalQuantity >= 1
                : totalQuantity == null;

        if (!valid) {
            throw new DomainException(GlobalErrorCode.VALIDATION_FAILED);
        }
    }

    // 구간 1개 이상, 금액은 모두 양수, 할인액 < 최소 주문 금액, 위 구간일수록 최소 주문 금액과 할인액이 모두 커야 함
    private static List<CartCouponTierSpec> validateTiers(List<CartCouponTierSpec> tierSpecs) {
        if (tierSpecs == null || tierSpecs.isEmpty()) {
            throw new DomainException(CouponErrorCode.COUPON_TIER_INVALID);
        }

        List<CartCouponTierSpec> sorted = tierSpecs.stream()
                .sorted(Comparator.comparingLong(CartCouponTierSpec::minOrderAmount))
                .toList();

        CartCouponTierSpec previous = null;
        for (CartCouponTierSpec tier : sorted) {
            if (tier.minOrderAmount() <= 0 || tier.discountAmount() <= 0
                    || tier.discountAmount() >= tier.minOrderAmount()) {
                throw new DomainException(CouponErrorCode.COUPON_TIER_INVALID);
            }
            if (previous != null
                    && (tier.minOrderAmount() == previous.minOrderAmount()
                    || tier.discountAmount() <= previous.discountAmount())) {
                throw new DomainException(CouponErrorCode.COUPON_TIER_INVALID);
            }
            previous = tier;
        }

        return sorted;
    }

    /**
     * 화면에 보여 줄 상태. 중지가 가장 먼저이고, 그다음 유효 기간으로 정한다.
     * 시작 시각은 포함하고 종료 시각은 포함하지 않는다.
     */
    public CouponDisplayStatus displayStatus(LocalDateTime now) {
        if (status == CouponStatus.STOPPED) {
            return CouponDisplayStatus.STOPPED;
        }
        if (now.isBefore(validFrom)) {
            return CouponDisplayStatus.SCHEDULED;
        }
        if (!now.isBefore(validUntil)) {
            return CouponDisplayStatus.ENDED;
        }
        return CouponDisplayStatus.ACTIVE;
    }

    // 지금 쓸 수 있는 쿠폰인지 (중지되지 않았고 유효 기간 안)
    public boolean isUsableAt(LocalDateTime now) {
        return displayStatus(now) == CouponDisplayStatus.ACTIVE;
    }

    // 수량 제한이 있고 남은 수량이 없는지
    public boolean isSoldOut() {
        return quantityLimited && remainingQuantity != null && remainingQuantity <= 0;
    }

    public boolean isCartCoupon() {
        return couponType == CouponType.CART;
    }

    /**
     * 장바구니 쿠폰 할인액. 주문 금액이 넘는 구간 중 가장 높은 구간의 할인액 하나만 준다.
     * 주문 금액은 상품 쿠폰 할인 후 상품 금액 합계(배송비 제외)이고, 어느 구간에도 못 미치면 0이다.
     */
    public long cartDiscountFor(long orderAmount) {
        if (!isCartCoupon()) {
            throw new IllegalStateException("장바구니 쿠폰만 구간 할인을 계산합니다. couponId=" + getId());
        }

        long discount = 0;
        for (CartCouponTier tier : tiers) {   // 최소 주문 금액 오름차순
            if (orderAmount >= tier.getMinOrderAmount()) {
                discount = tier.getDiscountAmount();
            }
        }
        return discount;
    }

    /**
     * 쿠폰 중지. 이미 중지됐거나 유효 기간이 끝난 쿠폰은 409-COUPON_STATE_INVALID.
     * 이미 주문에 예약된 쿠폰은 그대로 결제와 취소를 따라간다.
     */
    public void stop(CouponStoppedBy stoppedBy, String reason, LocalDateTime now) {
        CouponDisplayStatus current = displayStatus(now);
        if (current == CouponDisplayStatus.STOPPED || current == CouponDisplayStatus.ENDED) {
            throw new DomainException(CouponErrorCode.COUPON_STATE_INVALID);
        }

        this.status = CouponStatus.STOPPED;
        this.stoppedAt = now;
        this.stoppedBy = stoppedBy;
        this.stopReason = reason;
    }
}
