package com.backend.boundedContext.product.out;

import java.util.Collection;
import java.util.List;

/**
 * 개인화 추천 후보 조건. 판매 중이고 품절이 아닌 상품만 찾는 것은 구현이 항상 지킨다.
 *
 * @param seeds                기준 상품과 그 텍스트 (상품명·설명). 기준 상품마다 따로 비슷한 상품을 찾는다
 * @param categoryIds          후보를 찾을 소분류들 (기준 상품들 대분류의 사용 중인 소분류)
 * @param preferredCategoryIds 기준 상품들의 소분류. 이 소분류의 인기 상품도 후보로 넣는다
 * @param excludeIds           결과에서 뺄 상품 (기준 상품, 이미 구매·찜·장바구니에 담은 상품, 낮게 평가한 상품, 최근 본 상품)
 * @param candidatePerSeed     기준 상품 하나당 텍스트 후보 수
 * @param preferredSize        선호 소분류 후보 수
 */
public record ProductRecommendQuery(
        List<SeedText> seeds,
        Collection<Long> categoryIds,
        Collection<Long> preferredCategoryIds,
        Collection<Long> excludeIds,
        int candidatePerSeed,
        int preferredSize
) {

    /** 기준 상품 ID와 텍스트. 텍스트가 비어 있으면 그 기준 상품으로는 텍스트 후보를 찾지 않는다 */
    public record SeedText(long productId, String likeText) {
    }
}
