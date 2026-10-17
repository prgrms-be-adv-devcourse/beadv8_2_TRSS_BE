package com.backend.boundedContext.product.app.dto;

import com.backend.boundedContext.product.domain.ProductOption;

/**
 * 상품 상세의 옵션. 장바구니에는 optionId로 담는다.
 *
 * @param remainingStock 재고가 5개 이하일 때만 남은 수량, 그 외에는 null
 */
public record ProductOptionDto(
        Long optionId,
        String name,
        boolean soldOut,
        Integer remainingStock
) {
    public static ProductOptionDto from(ProductOption option) {
        return new ProductOptionDto(option.getId(), option.getName(), option.isSoldOut(), option.getLowStockRemaining());
    }
}
