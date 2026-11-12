package com.backend.boundedContext.product.app.dto;

import java.util.List;

/**
 * 만들 옵션 조합 하나
 *
 * @param values          옵션 값 (상품의 옵션 이름 순서대로). 옵션이 없는 상품이면 빈 목록
 * @param additionalPrice 상품 가격에 더하는 금액 (0 이상)
 * @param stock           초기 재고
 */
public record ProductOptionSpec(
        List<String> values,
        long additionalPrice,
        int stock
) {
    /** 옵션이 없는 상품의 옵션 (값 없음, 추가금 0) */
    public static ProductOptionSpec single(int stock) {
        return new ProductOptionSpec(List.of(), 0, stock);
    }
}
