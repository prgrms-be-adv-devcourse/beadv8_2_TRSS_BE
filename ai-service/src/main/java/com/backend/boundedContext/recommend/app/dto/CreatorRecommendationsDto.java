package com.backend.boundedContext.recommend.app.dto;

import com.backend.boundedContext.recommend.domain.IngredientTag;
import com.backend.boundedContext.recommend.domain.SkinConcern;
import com.backend.boundedContext.recommend.domain.SkinType;

import java.util.List;
import java.util.Set;

/** 피부가 비슷한 크리에이터 추천. 겹치는 정도가 큰 순서 */
public record CreatorRecommendationsDto(List<Item> items) {

    /**
     * @param activityName       인플루언서 활동명
     * @param matchedSkinType    피부 타입이 같은지
     * @param matchedConcerns    겹친 피부 고민
     * @param matchedIngredients 겹친 선호 성분
     * @param reason             예: "지성·트러블·모공 일치·나이아신아마이드 선호"
     */
    public record Item(Long influencerId, Long memberId, String activityName, SkinType skinType, Set<SkinConcern> concerns,
                       boolean matchedSkinType, Set<SkinConcern> matchedConcerns, Set<IngredientTag> matchedIngredients,
                       String reason) {
    }
}
