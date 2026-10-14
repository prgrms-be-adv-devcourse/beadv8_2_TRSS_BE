package com.backend.boundedContext.product.app.dto;

import com.backend.boundedContext.product.domain.ProductOption;

/** 옵션의 현재 재고 */
public record OptionStockDto(
        Long optionId,
        int stock
) {
    public static OptionStockDto from(ProductOption option) {
        return new OptionStockDto(option.getId(), option.getStock());
    }
}
