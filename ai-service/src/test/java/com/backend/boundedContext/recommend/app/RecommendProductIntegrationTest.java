package com.backend.boundedContext.recommend.app;

import com.backend.FakeEmbeddingModelConfig.FakeEmbeddingModel;
import com.backend.boundedContext.recommend.domain.BeautyCategory;
import com.backend.boundedContext.recommend.domain.IngredientTag;
import com.backend.boundedContext.recommend.domain.RecommendProduct;
import com.backend.boundedContext.recommend.domain.SkinConcern;
import com.backend.boundedContext.recommend.domain.SkinType;
import com.backend.boundedContext.recommend.out.BeautyProfileRepository;
import com.backend.boundedContext.recommend.out.RecommendProductRepository;
import com.backend.boundedContext.recommend.out.RecommendVectorRepository;
import com.backend.shared.product.event.ProductChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상품 변경 반영·재태깅·임베딩을 실제 DB(pgvector)로 확인한다. 임베딩은 가짜 EmbeddingModel이 만든다(OpenAI 호출 없음).
 * 임베딩은 트랜잭션 밖에서 돌므로 테스트에 트랜잭션을 걸지 않고 끝나면 지운다.
 */
@SpringBootTest
class RecommendProductIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 15, 12, 0);

    @Autowired
    private RecommendFacade recommendFacade;

    @Autowired
    private RecommendProductRepository recommendProductRepository;

    @Autowired
    private BeautyProfileRepository beautyProfileRepository;

    @Autowired
    private RecommendVectorRepository recommendVectorRepository;

    @Autowired
    private FakeEmbeddingModel fakeEmbeddingModel;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        fakeEmbeddingModel.failing = false;
        recommendProductRepository.deleteAll();
        beautyProfileRepository.deleteAll();
    }

    @Test
    @DisplayName("뷰티 상품: 전성분을 사전으로 읽어 태그를 저장하고, 관심 카테고리 코드를 소분류 이름으로 정한다")
    void applyBeauty() {
        recommendFacade.applyProductChange(event(1L, "수분 크림", "스킨케어", true, "에탄올, 정제수", true), T0);

        RecommendProduct product = recommendProductRepository.findById(1L).orElseThrow();
        assertThat(product.getIngredientTags()).containsExactly(IngredientTag.ALCOHOL);
        assertThat(product.isIngredientsKnown()).isTrue();
        assertThat(product.getBeautyCategory()).isEqualTo(BeautyCategory.SKINCARE);
        assertThat(embedding(1L)).isNull();
    }

    @Test
    @DisplayName("의류 상품: beauty=false, 태그 없음, 전성분 모름")
    void applyClothing() {
        recommendFacade.applyProductChange(event(2L, "티셔츠", "상의", false, null, true), T0);

        RecommendProduct product = recommendProductRepository.findById(2L).orElseThrow();
        assertThat(product.isBeauty()).isFalse();
        assertThat(product.getIngredientTags()).isEmpty();
        assertThat(product.isIngredientsKnown()).isFalse();
        assertThat(product.getBeautyCategory()).isNull();
    }

    @Test
    @DisplayName("이미 반영한 것보다 오래된 이벤트는 버린다")
    void olderEventIgnored() {
        recommendFacade.applyProductChange(event(1L, "새 이름", "스킨케어", true, "정제수", true), T0);
        recommendFacade.applyProductChange(event(1L, "옛 이름", "스킨케어", true, "정제수", false), T0.minusMinutes(1));

        RecommendProduct product = recommendProductRepository.findById(1L).orElseThrow();
        assertThat(product.getName()).isEqualTo("새 이름");
        assertThat(product.isOnSale()).isTrue();
    }

    @Test
    @DisplayName("임베딩: 판매 중 뷰티 상품만 채운다. 판매 여부만 바뀌면 임베딩을 두고, 이름이 바뀌면 지운다")
    void embedProducts() {
        recommendFacade.applyProductChange(event(1L, "수분 크림", "스킨케어", true, "정제수", true), T0);
        recommendFacade.applyProductChange(event(2L, "티셔츠", "상의", false, null, true), T0);

        recommendFacade.embedPending();
        assertThat(embedding(1L)).isNotNull();
        assertThat(embedding(2L)).isNull();   // 뷰티가 아니면 추천하지 않으므로 임베딩하지 않는다

        recommendFacade.applyProductChange(event(1L, "수분 크림", "스킨케어", true, "정제수", false), T0.plusMinutes(1));
        assertThat(embedding(1L)).isNotNull();

        recommendFacade.applyProductChange(event(1L, "진정 크림", "스킨케어", true, "정제수", true), T0.plusMinutes(2));
        assertThat(embedding(1L)).isNull();
    }

    @Test
    @DisplayName("임베딩하는 동안 상품 글이 바뀌었으면(해시가 다르면) 저장하지 않는다")
    void staleHashNotSaved() {
        recommendFacade.applyProductChange(event(1L, "수분 크림", "스킨케어", true, "정제수", true), T0);

        boolean saved = recommendVectorRepository.saveProductEmbedding(1L, "옛-해시", new float[1536]);

        assertThat(saved).isFalse();
        assertThat(embedding(1L)).isNull();
    }

    @Test
    @DisplayName("OpenAI가 실패하면 그대로 두고, 다음 시도 시각이 지나면 다시 채운다")
    void embeddingFailureRetried() {
        recommendFacade.applyProductChange(event(1L, "수분 크림", "스킨케어", true, "정제수", true), T0);

        fakeEmbeddingModel.failing = true;
        recommendFacade.embedPending();
        assertThat(embedding(1L)).isNull();

        fakeEmbeddingModel.failing = false;
        recommendFacade.embedPending();
        assertThat(embedding(1L)).isNull();   // 다음 시도 시각 전이라 건너뛴다

        jdbcTemplate.update("update recommend_product set embedding_retry_at = ? where id = 1", LocalDateTime.now().minusSeconds(1));
        recommendFacade.embedPending();
        assertThat(embedding(1L)).isNotNull();
    }

    @Test
    @DisplayName("설문: 저장하면 임베딩이 비고, 스케줄러가 채운다. 다시 저장하면 또 비운다")
    void embedProfile() {
        saveSurvey(SkinType.OILY);
        recommendFacade.embedPending();
        assertThat(profileEmbedding()).isNotNull();

        saveSurvey(SkinType.DRY);
        assertThat(profileEmbedding()).isNull();
    }

    @Test
    @DisplayName("기동 재태깅: 저장된 태그가 지금 사전과 다르면 다시 계산한다")
    void retag() {
        recommendFacade.applyProductChange(event(1L, "수분 크림", "스킨케어", true, "에탄올, 향료", true), T0);
        jdbcTemplate.update("update recommend_product set ingredient_tags = '{}' where id = 1");   // 사전이 바뀌기 전 상태 흉내

        int changed = recommendFacade.retagAll();

        assertThat(changed).isEqualTo(1);
        assertThat(recommendProductRepository.findById(1L).orElseThrow().getIngredientTags())
                .containsExactlyInAnyOrder(IngredientTag.ALCOHOL, IngredientTag.FRAGRANCE);
    }

    private void saveSurvey(SkinType skinType) {
        recommendFacade.saveSurvey(7L, skinType, List.of(SkinConcern.TROUBLE), List.of(IngredientTag.CICA), List.of(), List.of());
    }

    private static ProductChangedEvent event(Long id, String name, String category, boolean beauty, String ingredients, boolean onSale) {
        return new ProductChangedEvent(id, name, "설명", category, beauty, ingredients, 25_000, null, onSale);
    }

    private String embedding(Long productId) {
        return jdbcTemplate.queryForObject("select embedding::text from recommend_product where id = ?", String.class, productId);
    }

    private String profileEmbedding() {
        return jdbcTemplate.queryForObject("select embedding::text from beauty_profile where member_id = 7", String.class);
    }
}
