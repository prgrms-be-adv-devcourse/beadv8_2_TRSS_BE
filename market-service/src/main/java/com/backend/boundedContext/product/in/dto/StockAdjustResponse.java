package com.backend.boundedContext.product.in.dto;

/**
 * @param optionId 조정한 옵션 ID
 * @param stock    조정 후 재고
 */
public record StockAdjustResponse(
        Long optionId,
        int stock
) {
}
