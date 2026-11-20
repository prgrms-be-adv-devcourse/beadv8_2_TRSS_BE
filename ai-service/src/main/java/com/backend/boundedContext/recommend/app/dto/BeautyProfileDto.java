package com.backend.boundedContext.recommend.app.dto;

import com.backend.boundedContext.recommend.domain.BeautyCategory;
import com.backend.boundedContext.recommend.domain.BeautyProfile;
import com.backend.boundedContext.recommend.domain.IngredientTag;
import com.backend.boundedContext.recommend.domain.SkinConcern;
import com.backend.boundedContext.recommend.domain.SkinType;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 내 뷰티 프로필. 값은 코드이고, 여러 개인 값은 코드표 순서다
 *
 * @param avoidedIngredients 피하는 성분. 고르지 않았으면("없어요") 빈 목록
 * @param updatedAt          마지막으로 저장한 시각
 */
public record BeautyProfileDto(
        SkinType skinType,
        Set<SkinConcern> concerns,
        Set<IngredientTag> preferredIngredients,
        Set<IngredientTag> avoidedIngredients,
        Set<BeautyCategory> interestCategories,
        LocalDateTime updatedAt
) {
    public static BeautyProfileDto from(BeautyProfile profile) {
        return new BeautyProfileDto(profile.getSkinType(), profile.getConcerns(), profile.getPreferredIngredients(),
                profile.getAvoidedIngredients(), profile.getInterestCategories(), profile.getUpdatedAt());
    }
}
