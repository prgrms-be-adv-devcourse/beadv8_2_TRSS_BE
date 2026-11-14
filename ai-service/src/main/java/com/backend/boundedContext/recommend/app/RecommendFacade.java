package com.backend.boundedContext.recommend.app;

import com.backend.boundedContext.recommend.app.dto.BeautyProfileDto;
import com.backend.boundedContext.recommend.domain.BeautyCategory;
import com.backend.boundedContext.recommend.domain.IngredientTag;
import com.backend.boundedContext.recommend.domain.SkinConcern;
import com.backend.boundedContext.recommend.domain.SkinType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 추천 컨텍스트의 입구
 */
@Component
@RequiredArgsConstructor
public class RecommendFacade {

    private final RecommendSaveSurveyUseCase recommendSaveSurveyUseCase;
    private final RecommendGetSurveyUseCase recommendGetSurveyUseCase;

    @Transactional
    public BeautyProfileDto saveSurvey(Long memberId, SkinType skinType, List<SkinConcern> concerns,
                                       List<IngredientTag> preferredIngredients, List<IngredientTag> avoidedIngredients,
                                       List<BeautyCategory> interestCategories) {
        return recommendSaveSurveyUseCase.save(memberId, skinType, concerns, preferredIngredients, avoidedIngredients, interestCategories);
    }

    @Transactional(readOnly = true)
    public BeautyProfileDto getSurvey(Long memberId) {
        return recommendGetSurveyUseCase.get(memberId);
    }
}
