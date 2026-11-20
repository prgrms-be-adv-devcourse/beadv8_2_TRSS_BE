package com.backend.boundedContext.recommend.app;

import com.backend.boundedContext.recommend.app.dto.BeautyProfileDto;
import com.backend.boundedContext.recommend.app.dto.CreatorRecommendationsDto;
import com.backend.boundedContext.recommend.app.dto.ProductRecommendationsDto;
import com.backend.boundedContext.recommend.domain.BeautyCategory;
import com.backend.boundedContext.recommend.domain.IngredientTag;
import com.backend.boundedContext.recommend.domain.SkinConcern;
import com.backend.boundedContext.recommend.domain.SkinType;
import com.backend.shared.product.event.ProductChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 추천 컨텍스트의 입구
 */
@Component
@RequiredArgsConstructor
public class RecommendFacade {

    private final RecommendSaveSurveyUseCase recommendSaveSurveyUseCase;
    private final RecommendGetSurveyUseCase recommendGetSurveyUseCase;
    private final RecommendApplyProductChangeUseCase recommendApplyProductChangeUseCase;
    private final RecommendRetagUseCase recommendRetagUseCase;
    private final RecommendEmbedUseCase recommendEmbedUseCase;
    private final RecommendGetProductsUseCase recommendGetProductsUseCase;
    private final RecommendGetCreatorsUseCase recommendGetCreatorsUseCase;

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

    /** 맞춤 상품 추천. 비회원이면 memberId가 null */
    @Transactional(readOnly = true)
    public ProductRecommendationsDto getProducts(Long memberId, int size) {
        return recommendGetProductsUseCase.getProducts(memberId, size);
    }

    /** 나와 피부가 비슷한 크리에이터 */
    @Transactional(readOnly = true)
    public CreatorRecommendationsDto getCreators(Long memberId, int size) {
        return recommendGetCreatorsUseCase.getCreators(memberId, size);
    }

    /** market의 상품 변경을 추천용 사본에 반영한다. 컨슈머가 이벤트마다 부른다 */
    @Transactional
    public void applyProductChange(ProductChangedEvent event, LocalDateTime eventAt) {
        recommendApplyProductChangeUseCase.apply(event, eventAt);
    }

    /** 모든 추천 상품의 성분 태그를 지금 사전으로 다시 계산한다. 기동할 때 한 번 부른다 */
    @Transactional
    public int retagAll() {
        return recommendRetagUseCase.retagAll();
    }

    /** 비어 있는 임베딩(상품·설문)을 채운다. OpenAI를 부르므로 트랜잭션을 걸지 않는다 */
    public void embedPending() {
        recommendEmbedUseCase.embedPending();
    }
}
