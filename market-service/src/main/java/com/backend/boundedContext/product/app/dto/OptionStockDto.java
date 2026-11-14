package com.backend.boundedContext.product.app.dto;

import com.backend.boundedContext.product.domain.OptionStock;

/** 옵션의 현재 재고 */
public record OptionStockDto(
        Long optionId,
        int stock
) {
    public static OptionStockDto from(OptionStock stock) {
        return new OptionStockDto(stock.getId(), stock.getStock());
    }
}
