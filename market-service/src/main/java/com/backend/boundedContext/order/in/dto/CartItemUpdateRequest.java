package com.backend.boundedContext.order.in.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;

/**
 * 장바구니 항목 변경 요청
 */
public record CartItemUpdateRequest(
        @Min(1) Integer quantity,
        Boolean selected
) {
    @AssertTrue(message = "quantity와 selected 중 하나 이상을 보내야 합니다.")
    public boolean isAnyFieldPresent() {
        return quantity != null || selected != null;
    }
}
