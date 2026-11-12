package com.backend.boundedContext.product.app.dto;

import com.backend.boundedContext.product.domain.OptionStock;
import com.backend.boundedContext.product.domain.ProductOption;

import java.util.List;

/**
 * 판매자 상품 수정 폼의 옵션 조합
 *
 * @param name            표시 이름 (값을 " / "로 이은 것, 값이 없으면 "기본")
 * @param values          옵션 값 (상품의 옵션 이름 순서대로). 옵션이 없는 상품이면 빈 목록
 * @param additionalPrice 추가금
 * @param price           단가 (상품 가격 + 추가금)
 * @param stock           현재 재고
 */
public record SellerOptionDto(
        Long optionId,
        String name,
        List<String> values,
        long additionalPrice,
        long price,
        int stock
) {
    public static SellerOptionDto from(OptionStock stock) {
        ProductOption option = stock.getOption();
        return new SellerOptionDto(option.getId(), option.getDisplayName(), option.getValues(),
                option.getAdditionalPrice(), option.getUnitPrice(), stock.getStock());
    }
}
