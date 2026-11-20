package com.backend.boundedContext.recommend.app;

import com.backend.FakeEmbeddingModelConfig.FakeEmbeddingModel;
import com.backend.boundedContext.recommend.app.dto.ProductRecommendationsDto;
import com.backend.boundedContext.recommend.domain.EmbeddingSource;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA 보강(TRSS-300): 경합(해시·updated_at 조건), OpenAI 전체 장애 때 호출 수·ERROR 수, 긴 설명 기존 상품, 재태깅 → 추천 필터 실제 경로.
 * 실제 DB(pgvector)·가짜 EmbeddingModel(OpenAI 호출 없음).
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class RecommendEmbedRetryQaTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 15, 12, 0);
    private static final String ALERT = "recommend embedding keeps failing";

    @Autowired private RecommendFacade recommendFacade;
    @Autowired private RecommendRetagUseCase recommendRetagUseCase;
    @Autowired private RecommendProductRepository recommendProductRepository;
    @Autowired private BeautyProfileRepository beautyProfileRepository;
    @Autowired private RecommendVectorRepository recommendVectorRepository;
    @Autowired private FakeEmbeddingModel fakeEmbeddingModel;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    @AfterEach
    void tearDown() {
        fakeEmbeddingModel.reset();
        recommendProductRepository.deleteAll();
        beautyProfileRepository.deleteAll();
    }

    // ---------- 경합 ----------

    @Test
    @DisplayName("경합: 해시가 다르면 시도 횟수를 늘리지 않고 0, 다음 시도 시각도 정하지 않는다")
    void staleHashNotCounted() {
        apply(1L, "수분 크림", "설명", 25_000, T0);

        assertThat(recommendVectorRepository.increaseProductEmbeddingAttempts(1L, "옛-해시")).isZero();
        recommendVectorRepository.delayProductEmbedding(1L, "옛-해시", LocalDateTime.now().plusHours(1));

        assertThat(attempts("recommend_product", "id", 1L)).isZero();
        assertThat(retryAt("recommend_product", "id", 1L)).isNull();
    }

    @Test
    @DisplayName("경합: 설문 updated_at이 다르면 세지 않고 0")
    void staleProfileNotCounted() {
        recommendFacade.saveSurvey(7L, SkinType.OILY, List.of(SkinConcern.TROUBLE), List.of(), List.of(), List.of());

        assertThat(recommendVectorRepository.increaseProfileEmbeddingAttempts(7L, LocalDateTime.of(2000, 1, 1, 0, 0))).isZero();
        assertThat(attempts("beauty_profile", "member_id", 7L)).isZero();
    }

    @Test
    @DisplayName("경합: OpenAI 호출 중에 상품 글이 바뀌고 그 호출이 실패하면, 새 글의 재시도 상태를 건드리지 않고 ERROR도 없다")
    void productChangedDuringFailingCall(CapturedOutput output) {
        apply(1L, "수분 크림", "실패하는 설명", 25_000, T0);
        fakeEmbeddingModel.failingText = text -> {
            if (text.contains("실패하는 설명")) {
                // 호출 도중 컨슈머가 새 글을 반영했다고 친다(해시 변경 → 재시도 상태 초기화)
                recommendFacade.applyProductChange(event(1L, "수분 크림", "고친 설명", 25_000), T0.plusMinutes(1));
                return true;
            }
            return false;
        };

        recommendFacade.embedPending();

        assertThat(attempts("recommend_product", "id", 1L)).isZero();
        assertThat(retryAt("recommend_product", "id", 1L)).isNull();
        assertThat(recommendVectorRepository.findProductIdsToEmbed(20, LocalDateTime.now())).containsExactly(1L);
        assertThat(output.getAll()).doesNotContain(ALERT);
    }

    // ---------- OpenAI 전체 장애 ----------

    @Test
    @DisplayName("전체 장애: 한 회차 = 묶음 1회 + 한 건씩 3회(연달아 실패하면 나머지는 건너뜀) = 4회 호출. 실패한 묶음은 미뤄지므로 바로 다음 회차는 뒤 상품으로 넘어가고, 대상이 없으면 호출 0")
    void outageCallCount() {
        for (long id = 1; id <= 25; id++) apply(id, "상품" + id, "설명" + id, 25_000, T0);
        fakeEmbeddingModel.failing = true;

        int before = fakeEmbeddingModel.calls.get();
        recommendFacade.embedPending();
        assertThat(fakeEmbeddingModel.calls.get() - before).isEqualTo(4);

        before = fakeEmbeddingModel.calls.get();
        recommendFacade.embedPending();   // 5초 뒤 회차: 앞 20개는 1분 뒤로 밀렸으니 남은 5개
        assertThat(fakeEmbeddingModel.calls.get() - before).isEqualTo(4);

        before = fakeEmbeddingModel.calls.get();
        recommendFacade.embedPending();   // 대상 없음
        assertThat(fakeEmbeddingModel.calls.get() - before).isZero();
    }

    @Test
    @DisplayName("현재 동작(알림 폭주 위험): 전체 장애가 3회차 이상 이어지면 대기 중인 상품·설문 건마다 ERROR가 1번씩 난다(25개 → 25줄)")
    void outageAlertPerItem(CapturedOutput output) {
        for (long id = 1; id <= 25; id++) apply(id, "상품" + id, "설명" + id, 25_000, T0);
        fakeEmbeddingModel.failing = true;

        for (int round = 0; round < 3; round++) {
            recommendFacade.embedPending();
            recommendFacade.embedPending();   // 묶음 크기(20)를 넘는 나머지
            makeRetryDue();
        }

        assertThat(output.getAll().split(ALERT, -1)).hasSize(26);
        // 장애가 끝나도 마지막 실패 뒤 간격(3회차 뒤 30분)만큼은 기다린다
        assertThat(attempts("recommend_product", "id", 1L)).isEqualTo(3);
    }

    // ---------- 긴 설명 ----------

    @Test
    @DisplayName("긴 설명(배포 전 상태): 저장된 해시가 원문 기준이고 임베딩 전이면, 잘린 글로 임베딩해 옛 해시 조건으로 저장된다")
    void legacyLongDescriptionNotYetEmbedded() {
        String description = "가".repeat(5_000);
        apply(1L, "수분 크림", description, 25_000, T0);
        String legacyHash = EmbeddingSource.hash("카테고리: 스킨케어\n상품명: 수분 크림\n설명: " + description);
        jdbcTemplate.update("update recommend_product set embedding_source_hash = ? where id = 1", legacyHash);

        recommendFacade.embedPending();

        assertThat(embedding(1L)).isNotNull();
        assertThat(fakeEmbeddingModel.requestedTexts.getFirst()).hasSize("카테고리: 스킨케어\n상품명: 수분 크림\n설명: ".length() + 2_000);
    }

    @Test
    @DisplayName("긴 설명(이미 임베딩된 기존 상품): 다음 변경 이벤트가 가격만 바꿔도 해시가 바뀌어 한 번 다시 임베딩되고, 그 뒤 가격만 바뀌면 그대로")
    void legacyLongDescriptionAlreadyEmbedded() {
        String description = "가".repeat(3_000);
        apply(1L, "수분 크림", description, 25_000, T0);
        String legacyHash = EmbeddingSource.hash("카테고리: 스킨케어\n상품명: 수분 크림\n설명: " + description);
        jdbcTemplate.update("update recommend_product set embedding_source_hash = ?, embedding = cast(? as vector) where id = 1",
                legacyHash, "[" + "0.1,".repeat(1535) + "0.1]");

        recommendFacade.applyProductChange(event(1L, "수분 크림", description, 30_000), T0.plusMinutes(1));
        assertThat(embedding(1L)).as("가격만 바뀌었지만 해시 기준이 바뀌어 한 번 지워진다").isNull();

        recommendFacade.embedPending();
        assertThat(embedding(1L)).isNotNull();

        recommendFacade.applyProductChange(event(1L, "수분 크림", description, 35_000), T0.plusMinutes(2));
        assertThat(embedding(1L)).as("그 뒤로는 가격만 바뀌면 그대로").isNotNull();
    }

    // ---------- 재태깅 → 추천 필터 실제 경로 ----------

    @Test
    @DisplayName("배포 전 태그(괄호 안 쉼표로 향료 누락)인 상품이 기동 재태깅 뒤 향료 기피 회원 추천에서 빠진다")
    void retagThenFilter() {
        apply(1L, "향 토너", "설명", 25_000, T0, "정제수, 향료(리모넨, 리날룰)");
        apply(2L, "무향 크림", "설명", 25_000, T0, "정제수, 판테놀");
        // 예전 구현이 남긴 상태: 향료를 못 찾아 태그 없음, known=true
        jdbcTemplate.update("update recommend_product set ingredient_tags = '{}' where id = 1");
        recommendFacade.saveSurvey(7L, SkinType.OILY, List.of(), List.of(), List.of(IngredientTag.FRAGRANCE), List.of());
        assertThat(ids(recommendFacade.getProducts(7L, 10))).containsExactlyInAnyOrder(1L, 2L);   // 배포 전: 향료 상품이 추천됨

        assertThat(recommendFacade.retagAll()).isEqualTo(1);

        assertThat(ids(recommendFacade.getProducts(7L, 10))).containsExactly(2L);
    }

    @Test
    @DisplayName("현재 동작(기존 위험, 이번 변경으로 범위 증가): 재태깅 트랜잭션이 읽은 뒤 컨슈머가 상품을 바꾸면, 재태깅 커밋이 전체 컬럼을 옛 값으로 덮어쓴다")
    void retagLostUpdate() {
        apply(1L, "옛 이름", "설명", 25_000, T0, "정제수, 향료(리모넨, 리날룰)");
        jdbcTemplate.update("update recommend_product set ingredient_tags = '{}' where id = 1");

        transactionTemplate.executeWithoutResult(status -> {
            assertThat(recommendRetagUseCase.retagAll()).isEqualTo(1);   // 읽고 태그만 바꿈(아직 flush 전)
            // 그사이 다른 스레드(Kafka 컨슈머)가 새 이벤트를 반영하고 커밋
            CompletableFuture.runAsync(() -> apply(1L, "새 이름", "설명", 99_000, T0.plusMinutes(1), "정제수, 향료(리모넨, 리날룰)")).join();
        });

        RecommendProduct product = recommendProductRepository.findById(1L).orElseThrow();
        assertThat(product.getName()).isEqualTo("옛 이름");   // 새 이벤트가 사라짐
        assertThat(product.getPrice()).isEqualTo(25_000);
    }

    // ---------- 도우미 ----------

    private void apply(Long id, String name, String description, long price, LocalDateTime at) {
        apply(id, name, description, price, at, "정제수");
    }

    private void apply(Long id, String name, String description, long price, LocalDateTime at, String ingredients) {
        recommendFacade.applyProductChange(new ProductChangedEvent(id, name, description, "스킨케어", true, ingredients, price, null, true), at);
    }

    private static ProductChangedEvent event(Long id, String name, String description, long price) {
        return new ProductChangedEvent(id, name, description, "스킨케어", true, "정제수", price, null, true);
    }

    private static List<Long> ids(ProductRecommendationsDto result) {
        return result.items().stream().map(ProductRecommendationsDto.Item::productId).toList();
    }

    private void makeRetryDue() {
        jdbcTemplate.update("update recommend_product set embedding_retry_at = ? where embedding_retry_at is not null",
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
}
