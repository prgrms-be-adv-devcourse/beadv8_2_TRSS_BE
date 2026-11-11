package com.backend.boundedContext.product.app.dto;

import com.backend.boundedContext.product.domain.OptionStock;
import com.backend.boundedContext.product.domain.ProductOption;

import java.util.List;

/**
 * 상품 상세의 옵션 조합. 장바구니에는 optionId로 담는다.
 *
 * @param name            표시 이름 (값을 " / "로 이은 것, 옵션이 없는 상품이면 "기본")
 * @param values          옵션 값 (상품의 옵션 이름 순서대로). 옵션이 없는 상품이면 빈 목록
 * @param additionalPrice 추가금
 * @param price           단가 (상품 가격 + 추가금)
 * @param remainingStock  재고가 5개 이하일 때만 남은 수량, 그 외에는 null
 */
public record ProductOptionDto(
        Long optionId,
        String name,
        List<String> values,
        long additionalPrice,
        long price,
        boolean soldOut,
        Integer remainingStock
) {
    public static ProductOptionDto from(OptionStock stock) {
        ProductOption option = stock.getOption();
        return new ProductOptionDto(option.getId(), option.getDisplayName(), option.getValues(),
                option.getAdditionalPrice(), option.getUnitPrice(), stock.isSoldOut(), stock.getLowStockRemaining());
    }
}
