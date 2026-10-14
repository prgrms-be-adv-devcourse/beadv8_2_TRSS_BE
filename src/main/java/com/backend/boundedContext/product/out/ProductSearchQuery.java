package com.backend.boundedContext.product.out;

import com.backend.boundedContext.product.domain.ProductSort;

import java.util.Collection;

/**
 * 검색 구현({@link ProductSearchPort})에 넘기는 조건. 판매 중인 상품만 찾는 것은 구현이 항상 지킨다.
 *
 * @param keyword        상품명 부분 일치. null이면 조건 없음
 * @param categoryIds    이 소분류들의 상품만 (사용 중인 소분류만 담겨 온다). 비어 있으면 안 된다
 * @param excludeSoldOut true면 삭제되지 않은 옵션의 재고가 모두 0인 상품을 뺀다
 * @param sort           정렬
 */
public record ProductSearchQuery(
        String keyword,
        Collection<Long> categoryIds,
        boolean excludeSoldOut,
        ProductSort sort
) {
}
