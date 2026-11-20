package com.backend.boundedContext.product.in.dto;

import com.backend.boundedContext.product.app.dto.ProductOptionSpec;
import com.backend.boundedContext.product.domain.OptionStock;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductOption;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 옵션 조합 하나 (상품 등록의 options 항목, 옵션 추가 요청)
 *
 * @param values          옵션 값. 상품의 옵션 이름 순서대로 하나씩, 각각 최대 {@value ProductOption#MAX_VALUE_LENGTH}자. 옵션 없는 상품이면 빈 목록
 * @param additionalPrice 상품 가격에 더하는 금액 (0 이상, 보내지 않으면 0)
 * @param stock           초기 재고 (최대 {@value OptionStock#MAX_STOCK})
 */
public record ProductOptionRequest(
        @NotNull @Size(max = Product.MAX_OPTION_NAMES)
        List<@NotBlank @Size(max = ProductOption.MAX_VALUE_LENGTH) String> values,
        @PositiveOrZero Long additionalPrice,
        @NotNull @Positive @Max(OptionStock.MAX_STOCK) Integer stock
) {
    public ProductOptionSpec toSpec() {
        return new ProductOptionSpec(values, additionalPrice == null ? 0 : additionalPrice, stock);
    }
}
