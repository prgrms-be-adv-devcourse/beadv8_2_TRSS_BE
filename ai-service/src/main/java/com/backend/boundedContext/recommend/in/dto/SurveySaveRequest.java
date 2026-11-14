package com.backend.boundedContext.recommend.in.dto;

import com.backend.boundedContext.recommend.domain.BeautyCategory;
import com.backend.boundedContext.recommend.domain.BeautyProfile;
import com.backend.boundedContext.recommend.domain.IngredientTag;
import com.backend.boundedContext.recommend.domain.SkinConcern;
import com.backend.boundedContext.recommend.domain.SkinType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 뷰티 설문 저장
 * 선호 성분에는 PREFERRED, 피하는 성분에는 AVOIDED 코드만 올 수 있다(아니면 400-SURVEY_ANSWER_INVALID).
 */
public record SurveySaveRequest(
        @NotNull SkinType skinType,
        @NotNull @Size(max = BeautyProfile.MAX_CONCERNS) List<@NotNull SkinConcern> concerns,
        @NotNull List<@NotNull IngredientTag> preferredIngredients,
        @NotNull List<@NotNull IngredientTag> avoidedIngredients,
        @NotNull List<@NotNull BeautyCategory> interestCategories
) {
}
