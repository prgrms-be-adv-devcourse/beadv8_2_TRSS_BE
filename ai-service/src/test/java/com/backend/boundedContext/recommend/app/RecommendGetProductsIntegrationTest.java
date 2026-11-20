package com.backend.boundedContext.recommend.app;

import com.backend.FakeEmbeddingModelConfig;
import com.backend.FakeEmbeddingModelConfig.FakeEmbeddingModel;
import com.backend.boundedContext.recommend.app.dto.ProductRecommendationsDto;
import com.backend.boundedContext.recommend.app.dto.ProductRecommendationsDto.Item;
import com.backend.boundedContext.recommend.domain.BeautyCategory;
import com.backend.boundedContext.recommend.domain.IngredientTag;
import com.backend.boundedContext.recommend.domain.SkinType;
import com.backend.boundedContext.recommend.out.BeautyProfileRepository;
import com.backend.boundedContext.recommend.out.RecommendProductRepository;
import com.backend.shared.product.event.ProductChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static com.backend.boundedContext.recommend.domain.IngredientTag.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 추천 SQL을 실제 DB(pgvector)로 확인한다. 임베딩은 손으로 정한 벡터를 넣어 순서를 고정한다.
 * 프로필 벡터는 1번 축, 상품 벡터는 1번 축과의 각도로 가까운 정도를 정한다(cos 1.0 > 0.8 > 0.6 > 0).
 */
@SpringBootTest
class RecommendGetProductsIntegrationTest {

    private static final Long MEMBER_ID = 7L;
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 15, 12, 0);

    @Autowired
    private RecommendFacade recommendFacade;

    @Autowired
    private RecommendProductRepository recommendProductRepository;

    @Autowired
    private BeautyProfileRepository beautyProfileRepository;

    @Autowired
    private FakeEmbeddingModel fakeEmbeddingModel;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        recommendProductRepository.deleteAll();
        beautyProfileRepository.deleteAll();
    }

    @Test
    @DisplayName("하드 필터: 피하는 성분(알코올)이 든 상품은 프로필과 가장 가까워도 빠진다")
    void avoidedIngredientExcluded() {
        product(1L, "알코올 토너", "스킨케어", "에탄올, 정제수", 1.0);
        product(2L, "무알코올 크림", "스킨케어", "정제수, 판테놀", 0.6);
        survey(List.of(), List.of(ALCOHOL), List.of());

        assertThat(ids(recommend())).containsExactly(2L);
    }

    @Test
    @DisplayName("하드 필터: 괄호 안 쉼표가 있는 전성분(향료(리모넨, 리날룰))도 향료를 피하는 회원에게서 빠진다")
    void fragranceWithParenthesesExcluded() {
        product(1L, "향 토너", "스킨케어", "정제수, 향료(리모넨, 리날룰)", 1.0);
        product(2L, "글리세린 크림", "스킨케어", "정제수, 글리세린(식물유래, 99%), 향료", 0.9);
        product(3L, "무향 크림", "스킨케어", "정제수, 판테놀", 0.6);
        survey(List.of(), List.of(FRAGRANCE), List.of());

        assertThat(ids(recommend())).containsExactly(3L);
    }

    @Test
    @DisplayName("전성분 모르는 상품: 피하는 성분을 골랐으면 빠지고, 안 골랐으면 나온다")
    void unknownIngredients() {
        product(1L, "전성분 없는 크림", "스킨케어", null, 0.8);
        product(2L, "판테놀 크림", "스킨케어", "판테놀", 0.6);

        survey(List.of(), List.of(FRAGRANCE), List.of());
        assertThat(ids(recommend())).containsExactly(2L);

        survey(List.of(), List.of(), List.of());
        assertThat(ids(recommend())).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("판매 중지·의류 상품은 빠진다")
    void notOnSaleAndClothingExcluded() {
        product(1L, "수분 크림", "스킨케어", "정제수", 0.8);
        recommendFacade.applyProductChange(new ProductChangedEvent(2L, "중지 크림", "설명", "스킨케어", true, "정제수", 1000, null, false), T0);
        recommendFacade.applyProductChange(new ProductChangedEvent(3L, "티셔츠", "설명", "상의", false, null, 1000, null, true), T0);
        survey(List.of(), List.of(), List.of());

        assertThat(ids(recommend())).containsExactly(1L);
    }

    @Test
    @DisplayName("정렬: 프로필과 가까운 순. 유사도가 비슷하면 선호 성분·관심 카테고리 가점이 앞선다")
    void orderBySimilarityThenBonus() {
        product(1L, "먼 크림", "스킨케어", "정제수", 0.0);
        product(2L, "가까운 크림", "스킨케어", "정제수", 0.8);
        product(3L, "조금 덜 가까운 시카 선크림", "선케어", "병풀추출물", 0.78);   // 유사도 0.02 낮지만 시카(+0.05)·선케어(+0.05)
        survey(List.of(CICA), List.of(), List.of(BeautyCategory.SUNCARE));

        assertThat(ids(recommend())).containsExactly(3L, 2L, 1L);
    }

    @Test
    @DisplayName("응답: 기준(설문)과 이유 문구, 겹친 선호 성분을 담는다")
    void responseShape() {
        product(1L, "시카 크림", "스킨케어", "병풀추출물, 정제수", 0.8);
        survey(List.of(CICA, NIACINAMIDE), List.of(FRAGRANCE), List.of(BeautyCategory.SKINCARE));

        ProductRecommendationsDto result = recommend();

        assertThat(result.personalized()).isTrue();
        assertThat(result.basis().skinType()).isEqualTo(SkinType.OILY);
        assertThat(result.basis().avoidedIngredients()).containsExactly(FRAGRANCE);
        Item item = result.items().getFirst();
        assertThat(item.matchedIngredients()).containsExactly(CICA);
        assertThat(item.categoryName()).isEqualTo("스킨케어");
        assertThat(item.reason()).isEqualTo("시카(병풀) 포함 · 관심 카테고리 스킨케어 · 향료 없음");
    }

    @Test
    @DisplayName("프로필 임베딩이 아직 없으면 오류 없이 가점·최신순으로 준다")
    void profileWithoutEmbedding() {
        product(1L, "수분 크림", "스킨케어", "정제수", 0.8);
        product(2L, "시카 크림", "스킨케어", "병풀추출물", 0.0);
        recommendFacade.saveSurvey(MEMBER_ID, SkinType.OILY, List.of(), List.of(CICA), List.of(), List.of());   // 임베딩은 비어 있다

        assertThat(ids(recommend())).containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("설문 없는 회원·비회원: personalized=false, 판매 중 뷰티 상품 최신순, 이유 없음")
    void notPersonalized() {
        product(1L, "수분 크림", "스킨케어", "정제수", 0.8);
        product(2L, "선크림", "선케어", "정제수", 0.1);

        ProductRecommendationsDto anonymous = recommendFacade.getProducts(null, 10);
        ProductRecommendationsDto noSurvey = recommendFacade.getProducts(MEMBER_ID, 10);

        assertThat(anonymous.personalized()).isFalse();
        assertThat(anonymous.basis()).isNull();
        assertThat(ids(anonymous)).containsExactly(2L, 1L);
        assertThat(anonymous.items().getFirst().reason()).isNull();
        assertThat(ids(noSurvey)).containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("추천을 조회할 때 OpenAI(EmbeddingModel)를 부르지 않는다")
    void noEmbeddingCall() {
        product(1L, "수분 크림", "스킨케어", "정제수", 0.8);
        survey(List.of(), List.of(), List.of());
        int before = fakeEmbeddingModel.calls.get();

        recommend();

        assertThat(fakeEmbeddingModel.calls.get()).isEqualTo(before);
    }

    // 1번 축과 cos 값이 similarity인 단위 벡터를 가진 판매 중 뷰티 상품
    private void product(Long id, String name, String category, String ingredients, double similarity) {
        recommendFacade.applyProductChange(new ProductChangedEvent(id, name, "설명", category, true, ingredients, 25_000, null, true), T0);
        float[] v = new float[FakeEmbeddingModelConfig.DIMENSIONS];
        v[0] = (float) similarity;
        v[1] = (float) Math.sqrt(1 - similarity * similarity);
        jdbcTemplate.update("update recommend_product set embedding = cast(? as vector) where id = ?", vector(v), id);
    }

    private void survey(List<IngredientTag> preferred, List<IngredientTag> avoided, List<BeautyCategory> interests) {
        recommendFacade.saveSurvey(MEMBER_ID, SkinType.OILY, List.of(), preferred, avoided, interests);
        float[] v = new float[FakeEmbeddingModelConfig.DIMENSIONS];
        v[0] = 1f;
        jdbcTemplate.update("update beauty_profile set embedding = cast(? as vector) where member_id = ?", vector(v), MEMBER_ID);
    }

    private ProductRecommendationsDto recommend() {
        return recommendFacade.getProducts(MEMBER_ID, 10);
    }

    private static List<Long> ids(ProductRecommendationsDto result) {
        return result.items().stream().map(Item::productId).toList();
    }

    private static String vector(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) sb.append(i > 0 ? "," : "").append(v[i]);
        return sb.append(']').toString();
    }
}
