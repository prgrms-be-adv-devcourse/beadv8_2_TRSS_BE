package com.backend.boundedContext.product.in.dto;

import com.backend.boundedContext.product.app.dto.ProductOptionSpec;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductOption;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 상품 등록 요청. multipart의 request 파트(application/json)로 받는다.
 * 재고는 옵션 조합마다 받는다. 옵션이 없는 상품은 optionNames를 비우고(또는 보내지 않고) 값이 빈 조합 1개를 보낸다.
 * 예: {"optionNames": [], "options": [{"values": [], "stock": 30}]}
 *
 * @param name        상품명
 * @param price       가격 (원). 옵션별 단가는 가격 + 추가금
 * @param categoryId  소분류 ID (사용 중인 소분류만 가능)
 * @param description 상품 설명
 * @param optionNames 옵션 이름 (예: 색상, 사이즈). 최대 {@value Product#MAX_OPTION_NAMES}개, 각각 최대 {@value Product#MAX_OPTION_NAME_LENGTH}자. 없으면 옵션 없는 상품
 * @param options     옵션 조합. 1~{@value ProductOption#MAX_OPTIONS_PER_PRODUCT}개, 같은 조합은 한 번만
 */
public record ProductCreateRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull @Positive Long price,
        @NotNull Long categoryId,
        @NotBlank String description,
        @Size(max = Product.MAX_OPTION_NAMES) List<@NotBlank @Size(max = Product.MAX_OPTION_NAME_LENGTH) String> optionNames,
        @NotNull @Size(min = 1, max = ProductOption.MAX_OPTIONS_PER_PRODUCT) List<@Valid @NotNull ProductOptionRequest> options
) {
    /** 옵션 이름. 보내지 않았으면 빈 목록(옵션 없는 상품) */
    public List<String> optionNamesOrEmpty() {
        return optionNames == null ? List.of() : optionNames;
    }

    public List<ProductOptionSpec> toOptionSpecs() {
        return options.stream().map(ProductOptionRequest::toSpec).toList();
    }
}
