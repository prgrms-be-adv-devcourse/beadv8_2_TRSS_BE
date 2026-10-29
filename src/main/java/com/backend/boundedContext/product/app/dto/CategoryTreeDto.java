package com.backend.boundedContext.product.app.dto;

import java.util.List;

/**
 * 구매자용 카테고리 트리 노드. 사용 중인 카테고리만 담는다.
 *
 * @param productCount 판매 중인 상품 수 (품절 제외, 구매자 검색 기본값과 같음). 대분류는 아래 소분류 상품 수의 합
 */
public record CategoryTreeDto(
        Long categoryId,
        String name,
        int depth,
        int sortOrder,
        long productCount,
        List<CategoryTreeDto> children
) {
}
