package com.backend.boundedContext.product.in.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 옵션 수정 요청. 옵션 값은 바꿀 수 없고 추가금만 바꾼다.
 *
 * @param additionalPrice 상품 가격에 더하는 금액 (0 이상)
 */
public record OptionModifyRequest(
        @NotNull @PositiveOrZero Long additionalPrice
) {
}
