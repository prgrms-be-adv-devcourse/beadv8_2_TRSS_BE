package com.backend.boundedContext.recommend.app.dto;

import com.backend.boundedContext.recommend.domain.IngredientTag;
import com.backend.boundedContext.recommend.domain.SkinConcern;
import com.backend.boundedContext.recommend.domain.SkinType;

import java.util.List;
import java.util.Set;

/**
 * 맞춤 상품 추천
 *
 * @param personalized 설문으로 고른 결과면 true. 설문이 없거나 비회원이면 false(판매 중 뷰티 상품 최신순)
 * @param basis        추천 기준이 된 내 설문
 */
public record ProductRecommendationsDto(
        boolean personalized,
        Basis basis,
        List<Item> items
) {
    /** 화면 위의 "지성 · 트러블 · 모공 기준이에요, 향료가 든 상품은 뺐어요" 문구용 */
    public record Basis(SkinType skinType, Set<SkinConcern> concerns, Set<IngredientTag> avoidedIngredients) {
    }

    /**
     * @param price              정가
     * @param reason             추천 이유
     * @param matchedIngredients 상품에 있는 내 선호 성분 (코드표 순서)
     */
    public record Item(Long productId, String name, long price, String thumbnailUrl, String categoryName,
                       String reason, Set<IngredientTag> matchedIngredients) {
    }
}
