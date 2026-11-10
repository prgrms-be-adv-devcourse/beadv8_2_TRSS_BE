package com.backend.boundedContext.product.in.dto;

import com.backend.boundedContext.product.domain.OptionStock;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 옵션 재고 조정 요청
 *
 * @param changeQty 증감량. 늘리면 양수, 줄이면 음수 (0 불가, 절댓값은 최대 {@value OptionStock#MAX_STOCK})
 */
public record StockAdjustRequest(
        @NotNull @Min(-OptionStock.MAX_STOCK) @Max(OptionStock.MAX_STOCK) Integer changeQty
) {
    @AssertTrue(message = "증감량은 0일 수 없습니다.")
    public boolean isChangeQtyNotZero() {
        return changeQty == null || changeQty != 0;   // null은 @NotNull이 잡는다
    }
}
