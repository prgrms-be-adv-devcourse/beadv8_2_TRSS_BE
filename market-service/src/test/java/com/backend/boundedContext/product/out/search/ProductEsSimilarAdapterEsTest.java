package com.backend.boundedContext.product.out.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.backend.boundedContext.product.domain.ProductSimilarCandidate;
import com.backend.boundedContext.product.domain.ProductSimilarCandidates;
import com.backend.boundedContext.product.out.ProductJpaSimilarAdapter;
import com.backend.boundedContext.product.out.ProductSimilarQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * ES 비슷한 상품 후보 통합 테스트. 로컬 compose의 ES(9.4.5 + nori)가 필요하다.
 * 기본 test에서는 빠지고 {@code ./gradlew :market-service:esTest}로 돌린다.
 */
@Tag("es")
class ProductEsSimilarAdapterEsTest {
    private static final long SKINCARE = 10L;
    private static final long MAKEUP = 20L;
    private static final long TOP = 30L;
    private static final long BASE_ID = 100L;

    private ElasticsearchClient client;
    private ProductJpaSimilarAdapter fallback;
    private ProductEsIndexAdapter indexAdapter;
    private ProductEsSearchGuard guard;
    private ProductEsSimilarAdapter similarAdapter;
    private String alias;

    @BeforeEach
    void setUp() {
        client = ElasticsearchClient.of(b -> b.host("http://localhost:9200").usernameAndPassword("elastic", "elastic"));
        alias = "products_test_" + UUID.randomUUID().toString().substring(0, 8);
        fallback = mock(ProductJpaSimilarAdapter.class);
        indexAdapter = new ProductEsIndexAdapter(client, alias);
        guard = new ProductEsSearchGuard(indexAdapter, Duration.ofSeconds(30), Clock.systemDefaultZone());
        similarAdapter = new ProductEsSimilarAdapter(client, fallback, guard, alias);
    }

    @AfterEach
    void tearDown() throws Exception {
        List<String> indices = List.copyOf(client.indices()
                .get(g -> g.index(alias + "_*").allowNoIndices(true).ignoreUnavailable(true)).indices().keySet());
        if (!indices.isEmpty()) {
            client.indices().delete(d -> d.index(indices));
        }
        if (client.indices().exists(e -> e.index(alias)).value()) {
            client.indices().delete(d -> d.index(alias));
        }
        client.close();
    }

    @Test
    @DisplayName("상품명·설명의 형태소가 겹치는 같은 대분류 상품은 텍스트 점수와 함께, 같은 소분류 상품은 텍스트 점수 0으로 후보가 되고, "
            + "기준 상품·품절·다른 대분류 상품은 빠진다. 기준 상품 자기 자신의 점수(품절이어도)를 텍스트 기준값으로 준다")
    void candidates() throws Exception {
        indexAdapter.switchAlias(indexAdapter.createIndex());
        indexAdapter.upsert(List.of(
                doc(1, "히알루론 수분 크림", "건조한 피부에 수분을 채우는 보습 크림", SKINCARE, false, 0.1),
                doc(2, "진정 토너", "붉어진 피부를 가라앉히는 토너", SKINCARE, false, 0.9),
                doc(3, "수분 크림 품절", "건조한 피부에 수분을 채우는 보습 크림", SKINCARE, true, 0.5),
                doc(4, "수분 크림 티셔츠", "건조한 피부에 수분을 채우는 보습 크림", TOP, false, 0.5),
                doc(5, "수분 크림 쿠션", "건조한 피부에 수분을 채우는 보습 쿠션", MAKEUP, false, 0.2),
                doc(BASE_ID, "촉촉 수분 크림", "건조한 피부를 위한 보습 크림", SKINCARE, true, 0.5)));
        client.indices().refresh(r -> r.index(alias));

        ProductSimilarCandidates found = similarAdapter.findCandidates(query());
        Map<Long, ProductSimilarCandidate> result = found.candidates().stream()
                .collect(Collectors.toMap(ProductSimilarCandidate::productId, Function.identity()));

        assertThat(result).containsOnlyKeys(1L, 2L, 5L);
        assertThat(result.get(1L).textScore()).isPositive();
        assertThat(result.get(5L).textScore()).isPositive();   // 같은 대분류 다른 소분류
        assertThat(found.referenceTextScore()).isPositive();     // 기준 상품은 품절이어도 기준값을 준다
        assertThat(result.get(1L).effectivePrice()).isEqualTo(20_000);
        assertThat(result.get(1L).behaviorScore()).isEqualTo(0.1);
        assertThat(result.get(2L).textScore()).isZero();   // 같은 소분류 인기 후보
        assertThat(result.get(2L).categoryId()).isEqualTo(SKINCARE);
    }

    @Test
    @DisplayName("alias가 없으면(인덱스가 최신인지 모름) DB 후보로 대신한다")
    void fallbackWhenIndexUnknown() {
        ProductSimilarCandidates fromDb = ProductSimilarCandidates.withoutText(
                List.of(new ProductSimilarCandidate(7, SKINCARE, 10_000, 0, 0)));
        given(fallback.findCandidates(any())).willReturn(fromDb);

        ProductSimilarCandidates result = similarAdapter.findCandidates(query());

        assertThat(result.candidates()).isEqualTo(fromDb.candidates());
        assertThat(result.degraded()).isTrue();   // ES 대신 쓴 결과라 추천 계산이 저장하지 않는다
    }

    @Test
    @DisplayName("기준 상품 여러 개를 한 번에 찾으면 기준 상품마다 따로 후보·기준값을 준다 (msearch 한 번)")
    void batch() throws Exception {
        indexAdapter.switchAlias(indexAdapter.createIndex());
        indexAdapter.upsert(List.of(
                doc(1, "히알루론 수분 크림", "건조한 피부에 수분을 채우는 보습 크림", SKINCARE, false, 0.1),
                doc(2, "벨벳 매트 립스틱", "입술에 부드럽게 발리는 매트 립스틱", MAKEUP, false, 0.1),
                doc(BASE_ID, "촉촉 수분 크림", "건조한 피부를 위한 보습 크림", SKINCARE, false, 0.5),
                doc(200, "매트 립스틱", "입술에 발리는 립스틱", MAKEUP, false, 0.5)));
        client.indices().refresh(r -> r.index(alias));

        List<ProductSimilarCandidates> results = similarAdapter.findAllCandidates(List.of(query(),
                new ProductSimilarQuery(200, "매트 립스틱\n입술에 발리는 립스틱", MAKEUP, List.of(SKINCARE, MAKEUP), 50)));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).candidates()).extracting(ProductSimilarCandidate::productId).contains(1L).doesNotContain(BASE_ID);
        assertThat(results.get(1).candidates()).extracting(ProductSimilarCandidate::productId).contains(2L).doesNotContain(200L);
        assertThat(results).allSatisfy(r -> assertThat(r.referenceTextScore()).isPositive());
    }

    private static ProductSimilarQuery query() {
        return new ProductSimilarQuery(BASE_ID, "촉촉 수분 크림\n건조한 피부를 위한 보습 크림", SKINCARE,
                List.of(SKINCARE, MAKEUP), 50);
    }

    private static ProductSearchDocument doc(long id, String name, String description, long categoryId, boolean soldOut,
                                             double behaviorScore) {
        return new ProductSearchDocument(id, name, description, "팔레트 상점", categoryId, "ON_SALE", soldOut, 20_000, 0,
                0, 0, 0, 0, behaviorScore, id);
    }
}
