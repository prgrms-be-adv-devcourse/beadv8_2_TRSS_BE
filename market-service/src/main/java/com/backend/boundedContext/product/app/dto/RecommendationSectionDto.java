package com.backend.boundedContext.product.app.dto;

import com.backend.boundedContext.product.domain.RecommendBasis;

import java.util.List;

/**
 * 기준 상품별 추천 섹션 ("○○을 찜하셨네요, 이런 상품은 어때요?")
 *
 * @param basis            기준 상품의 출처
 * @param seedProductId    기준 상품 ID
 * @param seedProductName  기준 상품 이름
 * @param seedThumbnailUrl 기준 상품 대표 이미지 URL (제목 옆 썸네일). 대표 이미지가 없으면 null
 * @param items            비슷한 상품
 */
public record RecommendationSectionDto(
        RecommendBasis basis,
        Long seedProductId,
        String seedProductName,
        String seedThumbnailUrl,
        List<ProductSummaryDto> items
) {
}
