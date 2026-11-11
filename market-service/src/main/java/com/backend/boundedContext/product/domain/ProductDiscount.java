package com.backend.boundedContext.product.domain;

import com.backend.global.exception.DomainException;
import com.backend.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static jakarta.persistence.FetchType.LAZY;

/**
 * 상품 기간 할인. 판매자가 할인가와 기간을 정하면 그 기간에는 모든 구매자에게 할인가를 적용한다.
 * 할인은 상품 가격(기본가)에만 적용하고, 옵션 추가금은 할인한 기본가에 그대로 더한다.
 *
 * <p>상태(예약·진행 중·끝남)는 저장하지 않고 조회 시각으로 계산한다 ({@link #getStatus}).
 * 한 상품의 할인 기간은 겹칠 수 없는데, 다른 할인을 봐야 하므로 app 계층이 상품을 잠근 뒤 검사한다.
 */
@Entity
@Table(name = "product_discount")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductDiscount extends BaseIdAndTime {

    /** 할인율 하한 (%). 할인가는 정가의 99% 이하 */
    public static final int MIN_DISCOUNT_RATE = 1;

    /** 할인율 상한 (%). 할인가는 정가의 10% 이상 */
    public static final int MAX_DISCOUNT_RATE = 90;

    /** 할인 기간의 최대 길이 (년) */
    public static final int MAX_PERIOD_YEARS = 1;

    private static final long PERCENT = 100;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** 할인가 (원). 할인 기간에는 상품 가격 대신 이 값을 기본가로 쓴다 */
    @Column(nullable = false)
    private long salePrice;

    @Column(nullable = false)
    private LocalDateTime startsAt;

    /** 이 시각부터는 할인이 끝난 것이다 (startsAt 이상, endsAt 미만이 할인 기간) */
    @Column(nullable = false)
    private LocalDateTime endsAt;

    private LocalDateTime deletedAt;

    /**
     * 할인을 만든다. 판매 중지·삭제된 상품이면 409,
     * 할인가가 정가의 10~99%가 아니면 400, 기간이 맞지 않으면 400 ({@link #checkPeriod}).
     */
    public static ProductDiscount create(Product product, long salePrice, LocalDateTime startsAt, LocalDateTime endsAt,
                                         LocalDateTime now) {
        product.checkEditable();
        checkSalePrice(product.getPrice(), salePrice);
        checkPeriod(startsAt, endsAt, now);

        ProductDiscount discount = new ProductDiscount();
        discount.product = product;
        discount.salePrice = salePrice;
        discount.startsAt = startsAt;
        discount.endsAt = endsAt;
        return discount;
    }

    /** 시작 전인 할인의 할인가와 기간을 바꾼다. 시작했거나 끝났으면 409. 검사 규칙은 {@link #create}와 같다. */
    public void modify(long salePrice, LocalDateTime startsAt, LocalDateTime endsAt, LocalDateTime now) {
        checkScheduled(now);
        checkSalePrice(product.getPrice(), salePrice);
        checkPeriod(startsAt, endsAt, now);
        this.salePrice = salePrice;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
    }

    /** 시작 전인 할인을 지운다 (소프트 삭제). 시작했거나 끝났으면 409. */
    public void delete(LocalDateTime now) {
        checkScheduled(now);
        this.deletedAt = now;
    }

    /** 진행 중인 할인을 지금 끝낸다 (끝 시각을 지금으로). 진행 중이 아니면 409. 할인가는 바꾸지 않는다. */
    public void end(LocalDateTime now) {
        product.checkEditable();
        if (getStatus(now) != ProductDiscountStatus.ACTIVE) {
            throw new DomainException(ProductErrorCode.DISCOUNT_NOT_ACTIVE);
        }
        this.endsAt = now;
    }

    public ProductDiscountStatus getStatus(LocalDateTime now) {
        if (now.isBefore(startsAt)) return ProductDiscountStatus.SCHEDULED;
        if (now.isBefore(endsAt)) return ProductDiscountStatus.ACTIVE;
        return ProductDiscountStatus.ENDED;
    }

    public boolean isActiveAt(LocalDateTime now) {
        return getStatus(now) == ProductDiscountStatus.ACTIVE;
    }

    /** 이 할인의 기간이 [startsAt, endsAt)과 겹치는지 */
    public boolean overlaps(LocalDateTime startsAt, LocalDateTime endsAt) {
        return this.startsAt.isBefore(endsAt) && startsAt.isBefore(this.endsAt);
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** 지금 상품 가격 기준 할인율 (%, 소수점 내림) */
    public int getDiscountRate() {
        return discountRate(product.getPrice(), salePrice);
    }

    /** 할인율 = (정가 − 할인가) × 100 / 정가 (%, 소수점 내림) */
    public static int discountRate(long price, long salePrice) {
        return (int) (Math.multiplyExact(price - salePrice, PERCENT) / price);
    }

    /**
     * 할인가가 정가의 10~99%인지 정확한 비율로 본다 (price × 10 ≤ salePrice × 100 ≤ price × 99). 아니면 400.
     * 내림한 할인율로 보면 90.9% 할인도 90%로 통과하므로, 비율로 봐서 표시 할인율이 항상 1~90이 되게 한다.
     */
    public static void checkSalePrice(long price, long salePrice) {
        long scaledSale = Math.multiplyExact(salePrice, PERCENT);
        boolean tooLow = scaledSale < Math.multiplyExact(price, PERCENT - MAX_DISCOUNT_RATE);
        boolean tooHigh = scaledSale > Math.multiplyExact(price, PERCENT - MIN_DISCOUNT_RATE);
        if (salePrice <= 0 || tooLow || tooHigh) {
            throw new DomainException(ProductErrorCode.DISCOUNT_PRICE_INVALID);
        }
    }

    /**
     * 시작 시각은 지금 이후, 끝 시각은 시작 시각 뒤 {@value #MAX_PERIOD_YEARS}년 이내여야 한다. 아니면 400.
     * 시작 시각은 분 단위로 본다. 화면이 "지금"을 보내도 요청이 도착하는 사이에 과거가 되지 않게 지금이 속한 분까지는 허용한다.
     */
    private static void checkPeriod(LocalDateTime startsAt, LocalDateTime endsAt, LocalDateTime now) {
        boolean startsInPast = startsAt.isBefore(now.truncatedTo(ChronoUnit.MINUTES));
        boolean invalidOrder = !startsAt.isBefore(endsAt);
        boolean tooLong = endsAt.isAfter(startsAt.plusYears(MAX_PERIOD_YEARS));
        if (startsInPast || invalidOrder || tooLong) {
            throw new DomainException(ProductErrorCode.DISCOUNT_PERIOD_INVALID);
        }
    }

    private void checkScheduled(LocalDateTime now) {
        product.checkEditable();
        if (getStatus(now) != ProductDiscountStatus.SCHEDULED) {
            throw new DomainException(ProductErrorCode.DISCOUNT_NOT_SCHEDULED);
        }
    }
}
