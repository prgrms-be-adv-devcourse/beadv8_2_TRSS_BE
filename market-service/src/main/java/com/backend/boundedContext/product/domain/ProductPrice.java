package com.backend.boundedContext.product.domain;

import java.time.LocalDateTime;

/**
 * 어느 시각의 상품 가격. 정가와 그 시각에 진행 중인 할인(없으면 null)으로 계산한다.
 * 할인은 기본가에만 적용하고, 옵션 단가는 할인한 기본가 + 추가금이다.
 *
 * @param listPrice      정가 (상품 가격)
 * @param activeDiscount 진행 중인 할인. 없으면 null
 */
public record ProductPrice(long listPrice, ProductDiscount activeDiscount) {

    public static ProductPrice of(Product product, ProductDiscount activeDiscount) {
        return new ProductPrice(product.getPrice(), activeDiscount);
    }

    public boolean isDiscounted() {
        return activeDiscount != null;
    }

    /** 지금 적용하는 기본가. 할인 중이면 할인가, 아니면 정가 */
    public long basePrice() {
        return isDiscounted() ? activeDiscount.getSalePrice() : listPrice;
    }

    /** 할인가. 할인 중이 아니면 null */
    public Long salePrice() {
        return isDiscounted() ? activeDiscount.getSalePrice() : null;
    }

    /** 할인율 (%, 소수점 내림). 할인 중이 아니면 null */
    public Integer discountRate() {
        return isDiscounted() ? ProductDiscount.discountRate(listPrice, activeDiscount.getSalePrice()) : null;
    }

    /** 할인이 끝나는 시각. 할인 중이 아니면 null */
    public LocalDateTime discountEndsAt() {
        return isDiscounted() ? activeDiscount.getEndsAt() : null;
    }

    /** 옵션 단가 = 지금 기본가 + 추가금 */
    public long unitPrice(ProductOption option) {
        return Math.addExact(basePrice(), option.getAdditionalPrice());
    }

    /** 옵션 정가 단가 = 정가 + 추가금 */
    public long listUnitPrice(ProductOption option) {
        return Math.addExact(listPrice, option.getAdditionalPrice());
    }
}
