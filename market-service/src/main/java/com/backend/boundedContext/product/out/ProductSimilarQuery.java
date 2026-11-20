package com.backend.boundedContext.product.out;

import java.util.Collection;

/**
 * 비슷한 상품 후보 조건. 판매 중이고 품절이 아닌 상품만 찾고, 기준 상품은 빼는 것은 구현이 항상 지킨다.
 *
 * @param productId     기준 상품 ID (결과에서 뺀다)
 * @param likeText      기준 상품의 텍스트 (상품명·설명). 이 텍스트와 비슷한 상품을 찾는다
 * @param categoryId    기준 상품의 소분류 ID. 이 소분류의 인기 상품도 후보로 넣는다
 * @param categoryIds   후보를 찾을 소분류들 (기준 상품 대분류의 사용 중인 소분류)
 * @param candidateSize 텍스트 후보·같은 소분류 후보를 각각 이만큼까지
 */
public record ProductSimilarQuery(
        long productId,
        String likeText,
        long categoryId,
        Collection<Long> categoryIds,
        int candidateSize
) {
}
