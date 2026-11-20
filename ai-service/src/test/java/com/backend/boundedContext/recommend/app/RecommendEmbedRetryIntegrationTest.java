package com.backend.boundedContext.recommend.app;

import com.backend.FakeEmbeddingModelConfig.FakeEmbeddingModel;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 임베딩 실패 격리·재시도 간격·알림·설명 길이 제한을 실제 DB로 확인한다. 임베딩은 가짜 EmbeddingModel이 만든다(OpenAI 호출 없음).
 * 임베딩은 트랜잭션 밖에서 돌므로 테스트에 트랜잭션을 걸지 않고 끝나면 지운다.
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class RecommendEmbedRetryIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 15, 12, 0);
    private static final String ALERT = "recommend embedding keeps failing";

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
        fakeEmbeddingModel.reset();
        recommendProductRepository.deleteAll();
        beautyProfileRepository.deleteAll();
    }

    @Test
    @DisplayName("묶음 중 한 상품만 계속 실패하면 나머지는 저장되고, 실패한 상품은 다음 시도 시각이 미래가 되어 대상에서 빠진다")
    void failingProductIsolated() {
        apply(1L, "수분 크림", "설명");
        apply(2L, "진정 크림", "실패하는 설명");
        apply(3L, "선크림", "설명");
        fakeEmbeddingModel.failingText = text -> text.contains("실패하는 설명");

        recommendFacade.embedPending();

        assertThat(embedding(1L)).isNotNull();
        assertThat(embedding(2L)).isNull();
        assertThat(embedding(3L)).isNotNull();
        assertThat(attempts("recommend_product", "id", 2L)).isEqualTo(1);
        assertThat(retryAt("recommend_product", "id", 2L)).isAfter(LocalDateTime.now());
        assertThat(recommendVectorRepository.findProductIdsToEmbed(20, LocalDateTime.now())).doesNotContain(2L);

        // 다음 시도 시각이 지나면 다시 대상이 된다
        makeRetryDue("recommend_product");
        assertThat(recommendVectorRepository.findProductIdsToEmbed(20, LocalDateTime.now())).containsExactly(2L);
    }

    @Test
    @DisplayName("다시 시도하기까지 기다리는 시간은 실패할수록 늘어난다(1·5·30·60분, 그 뒤로 60분)")
    void retryDelayGrows() {
        apply(1L, "진정 크림", "실패하는 설명");
        fakeEmbeddingModel.failingText = text -> text.contains("실패하는 설명");

        for (long expectedMinutes : new long[]{1, 5, 30, 60, 60}) {
            LocalDateTime before = LocalDateTime.now();
            recommendFacade.embedPending();
            assertThat(retryAt("recommend_product", "id", 1L))
                    .isAfter(before.plusMinutes(expectedMinutes).minusSeconds(1))
                    .isBefore(LocalDateTime.now().plusMinutes(expectedMinutes).plusSeconds(1));
            makeRetryDue("recommend_product");
        }
        assertThat(attempts("recommend_product", "id", 1L)).isEqualTo(5);
    }

    @Test
    @DisplayName("연속 실패가 기준 횟수(3)에 처음 닿을 때만 ERROR를 한 번 남기고, 상품 설명 원문은 로그에 없다")
    void alertOnce(CapturedOutput output) {
        apply(1L, "진정 크림", "실패하는 설명");
        fakeEmbeddingModel.failingText = text -> text.contains("실패하는 설명");

        for (int i = 0; i < 5; i++) {
            recommendFacade.embedPending();
            makeRetryDue("recommend_product");
        }

        assertThat(attempts("recommend_product", "id", 1L)).isEqualTo(5);
        assertThat(output.getAll().split(ALERT, -1)).hasSize(2);   // 한 번
        assertThat(output.getAll()).contains(ALERT + " target=PRODUCT id=1 attempts=3");
        assertThat(output.getAll()).doesNotContain("실패하는 설명");
    }

    @Test
    @DisplayName("다시 시도해 성공하면 임베딩을 저장하고 시도 횟수·다음 시도 시각을 지운다")
    void successResetsRetryState() {
        apply(1L, "진정 크림", "설명");
        fakeEmbeddingModel.failing = true;
        recommendFacade.embedPending();
        assertThat(attempts("recommend_product", "id", 1L)).isEqualTo(1);

        fakeEmbeddingModel.failing = false;
        makeRetryDue("recommend_product");
        recommendFacade.embedPending();

        assertThat(embedding(1L)).isNotNull();
        assertThat(attempts("recommend_product", "id", 1L)).isZero();
        assertThat(retryAt("recommend_product", "id", 1L)).isNull();
    }

    @Test
    @DisplayName("상품 글이 바뀌면 재시도 상태를 지워 바로 다시 시도한다")
    void changedProductResetsRetryState() {
        apply(1L, "진정 크림", "실패하는 설명");
        fakeEmbeddingModel.failingText = text -> text.contains("실패하는 설명");
        recommendFacade.embedPending();
        assertThat(retryAt("recommend_product", "id", 1L)).isNotNull();

        recommendFacade.applyProductChange(event(1L, "진정 크림", "고친 설명"), T0.plusMinutes(1));

        assertThat(attempts("recommend_product", "id", 1L)).isZero();
        assertThat(retryAt("recommend_product", "id", 1L)).isNull();
        recommendFacade.embedPending();
        assertThat(embedding(1L)).isNotNull();
    }

    @Test
    @DisplayName("설문도 실패하면 다음 시도 시각까지 대상에서 빠지고, 성공하면 재시도 상태를 지운다")
    void failingProfile() {
        recommendFacade.saveSurvey(7L, SkinType.OILY, List.of(SkinConcern.TROUBLE), List.of(IngredientTag.CICA), List.of(), List.of());
        recommendFacade.saveSurvey(8L, SkinType.DRY, List.of(SkinConcern.TROUBLE), List.of(IngredientTag.CICA), List.of(), List.of());
        fakeEmbeddingModel.failingText = text -> text.contains(SkinType.OILY.getLabel());

        recommendFacade.embedPending();

        assertThat(profileEmbedding(7L)).isNull();
        assertThat(profileEmbedding(8L)).isNotNull();
        assertThat(attempts("beauty_profile", "member_id", 7L)).isEqualTo(1);
        assertThat(retryAt("beauty_profile", "member_id", 7L)).isAfter(LocalDateTime.now());
        assertThat(recommendVectorRepository.findProfileMemberIdsToEmbed(20, LocalDateTime.now())).doesNotContain(7L);

        fakeEmbeddingModel.failingText = text -> false;
        makeRetryDue("beauty_profile");
        recommendFacade.embedPending();

        assertThat(profileEmbedding(7L)).isNotNull();
        assertThat(attempts("beauty_profile", "member_id", 7L)).isZero();
        assertThat(retryAt("beauty_profile", "member_id", 7L)).isNull();
    }

    @Test
    @DisplayName("OpenAI 장애로 한 건씩 다시 보낸 것이 3번 연달아 실패하면 나머지는 보내지 않고, 모두 실패로 남겨 재시도 간격을 탄다")
    void outageSkipsRest() {
        for (long id = 1; id <= 5; id++) apply(id, "크림" + id, "설명");
        fakeEmbeddingModel.failing = true;
        int callsBefore = fakeEmbeddingModel.calls.get();

        recommendFacade.embedPending();

        assertThat(fakeEmbeddingModel.calls.get() - callsBefore).isEqualTo(1 + 3);   // 묶음 1번 + 한 건씩 3번
        for (long id = 1; id <= 5; id++) {
            assertThat(embedding(id)).isNull();
            assertThat(attempts("recommend_product", "id", id)).isEqualTo(1);
            assertThat(retryAt("recommend_product", "id", id)).isAfter(LocalDateTime.now());
        }
    }

    @Test
    @DisplayName("아주 긴 설명은 임베딩 글에서만 2,000자로 자르고 임베딩을 저장한다. DB의 설명 원문은 그대로")
    void longDescriptionTruncated() {
        String description = "가".repeat(RecommendProduct.EMBEDDING_DESCRIPTION_MAX_LENGTH) + "나".repeat(10_000);
        apply(1L, "수분 크림", description);
        // 입력 한도를 흉내 낸다: 잘리지 않은 글이 오면 실패
        fakeEmbeddingModel.failingText = text -> text.contains("나");

        recommendFacade.embedPending();

        assertThat(embedding(1L)).isNotNull();
        assertThat(fakeEmbeddingModel.requestedTexts).hasSize(1);
        assertThat(fakeEmbeddingModel.requestedTexts.getFirst())
                .endsWith("\n설명: " + "가".repeat(RecommendProduct.EMBEDDING_DESCRIPTION_MAX_LENGTH));
        assertThat(recommendProductRepository.findById(1L).orElseThrow().getDescription()).isEqualTo(description);
    }

    private void apply(Long id, String name, String description) {
        recommendFacade.applyProductChange(event(id, name, description), T0);
    }

    private static ProductChangedEvent event(Long id, String name, String description) {
        return new ProductChangedEvent(id, name, description, "스킨케어", true, "정제수", 25_000, null, true);
    }

    // 시간이 지난 것처럼 다음 시도 시각을 과거로 돌린다
    private void makeRetryDue(String table) {
        jdbcTemplate.update("update " + table + " set embedding_retry_at = ? where embedding_retry_at is not null",
                LocalDateTime.now().minusSeconds(1));
    }

    private int attempts(String table, String key, Long id) {
        return jdbcTemplate.queryForObject("select embedding_attempts from " + table + " where " + key + " = ?", Integer.class, id);
    }

    private LocalDateTime retryAt(String table, String key, Long id) {
        return jdbcTemplate.queryForObject("select embedding_retry_at from " + table + " where " + key + " = ?", LocalDateTime.class, id);
    }

    private String embedding(Long productId) {
        return jdbcTemplate.queryForObject("select embedding::text from recommend_product where id = ?", String.class, productId);
    }

    private String profileEmbedding(Long memberId) {
        return jdbcTemplate.queryForObject("select embedding::text from beauty_profile where member_id = ?", String.class, memberId);
    }
}
