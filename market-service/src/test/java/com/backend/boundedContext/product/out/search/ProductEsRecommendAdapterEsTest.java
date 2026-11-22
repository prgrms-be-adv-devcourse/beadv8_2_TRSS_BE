package com.backend.boundedContext.product.out.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.backend.boundedContext.product.domain.RecommendCandidate;
import com.backend.boundedContext.product.domain.RecommendCandidates;
import com.backend.boundedContext.product.out.ProductJpaRecommendAdapter;
import com.backend.boundedContext.product.out.ProductRecommendQuery;
import com.backend.boundedContext.product.out.ProductRecommendQuery.SeedText;
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
 * ES 개인화 추천 후보 통합 테스트. 로컬 compose의 ES(9.4.5 + nori)가 필요하다.
 * 기본 test에서는 빠지고 {@code ./gradlew :market-service:esTest}로 돌린다.
 */
@Tag("es")
class ProductEsRecommendAdapterEsTest {
    private static final long SKINCARE = 10L;
    private static final long MAKEUP = 20L;
    private static final long CREAM_SEED = 100L;
    private static final long LIP_SEED = 200L;

    private ElasticsearchClient client;
    private ProductJpaRecommendAdapter fallback;
    private ProductEsIndexAdapter indexAdapter;
    private ProductEsSearchGuard guard;
    private ProductEsRecommendAdapter adapter;
    private String alias;

    @BeforeEach
    void setUp() {
        client = ElasticsearchClient.of(b -> b.host("http://localhost:9200").usernameAndPassword("elastic", "elastic"));
        alias = "products_test_" + UUID.randomUUID().toString().substring(0, 8);
        fallback = mock(ProductJpaRecommendAdapter.class);
        indexAdapter = new ProductEsIndexAdapter(client, alias);
        guard = new ProductEsSearchGuard(indexAdapter, Duration.ofSeconds(30), Clock.systemDefaultZone());
        adapter = new ProductEsRecommendAdapter(client, fallback, guard, alias);
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
    @DisplayName("기준 상품마다 텍스트 점수를 따로 주고(추천 이유용), 기준 상품 자신의 점수를 기준값으로 준다(품절이어도). "
            + "선호 소분류 인기 상품도 후보가 되고, 뺄 상품·품절·기준 상품은 후보에서 빠진다")
    void candidatesPerSeed() throws Exception {
        indexAdapter.switchAlias(indexAdapter.createIndex());
        indexAdapter.upsert(List.of(
                doc(1, "히알루론 수분 크림", "건조한 피부에 수분을 채우는 보습 크림", SKINCARE, false, 0.1),
                doc(2, "벨벳 매트 립스틱", "입술에 부드럽게 발리는 매트 립스틱", MAKEUP, false, 0.1),
                doc(3, "진정 토너", "붉어진 피부를 가라앉히는 토너", SKINCARE, false, 0.9),
                doc(4, "수분 크림 이미 찜함", "건조한 피부에 수분을 채우는 보습 크림", SKINCARE, false, 0.5),
                doc(5, "수분 크림 품절", "건조한 피부에 수분을 채우는 보습 크림", SKINCARE, true, 0.5),
                doc(CREAM_SEED, "촉촉 수분 크림", "건조한 피부를 위한 보습 크림", SKINCARE, true, 0.5),
                doc(LIP_SEED, "매트 립스틱", "입술에 발리는 립스틱", MAKEUP, false, 0.5)));
        client.indices().refresh(r -> r.index(alias));

        RecommendCandidates found = adapter.findCandidates(new ProductRecommendQuery(
                List.of(new SeedText(CREAM_SEED, "촉촉 수분 크림\n건조한 피부를 위한 보습 크림"),
                        new SeedText(LIP_SEED, "매트 립스틱\n입술에 발리는 립스틱")),
                List.of(SKINCARE, MAKEUP), List.of(SKINCARE), List.of(CREAM_SEED, LIP_SEED, 4L), 30, 50));
        Map<Long, RecommendCandidate> byId = found.candidates().stream()
                .collect(Collectors.toMap(RecommendCandidate::productId, Function.identity()));

        assertThat(byId).containsOnlyKeys(1L, 2L, 3L);
        assertThat(byId.get(1L).textScores()).containsOnlyKeys(CREAM_SEED);   // 크림 기준으로만 이어진다
        assertThat(byId.get(2L).textScores()).containsOnlyKeys(LIP_SEED);
        assertThat(byId.get(3L).textScores()).isEmpty();                       // 선호 소분류 인기 후보
        assertThat(found.referenceScores()).containsOnlyKeys(CREAM_SEED, LIP_SEED);   // 품절인 크림 기준도 기준값을 준다
    }

    @Test
    @DisplayName("alias가 없으면(인덱스가 최신인지 모름) DB 후보로 대신한다")
    void fallbackWhenIndexUnknown() {
        RecommendCandidates fromDb = RecommendCandidates.withoutText(List.of());
        given(fallback.findCandidates(any())).willReturn(fromDb);

        RecommendCandidates result = adapter.findCandidates(new ProductRecommendQuery(List.of(new SeedText(1L, "크림")),
                List.of(SKINCARE), List.of(SKINCARE), List.of(), 30, 50));

        assertThat(result.candidates()).isEqualTo(fromDb.candidates());
        assertThat(result.degraded()).isTrue();   // ES 대신 쓴 결과라 저장하지 않는다
    }

    @Test
    @DisplayName("기준 상품의 소분류가 사용 중지돼(후보 소분류에 없어도) 기준 상품 자신은 찾아 기준값을 준다")
    void referenceForSeedInInactiveCategory() throws Exception {
        indexAdapter.switchAlias(indexAdapter.createIndex());
        indexAdapter.upsert(List.of(
                doc(1, "히알루론 수분 크림", "건조한 피부에 수분을 채우는 보습 크림", SKINCARE, false, 0.1),
                doc(CREAM_SEED, "촉촉 수분 크림", "건조한 피부를 위한 보습 크림", MAKEUP, false, 0.5)));   // 꺼진 소분류라고 친다
        client.indices().refresh(r -> r.index(alias));

        RecommendCandidates found = adapter.findCandidates(new ProductRecommendQuery(
                List.of(new SeedText(CREAM_SEED, "촉촉 수분 크림\n건조한 피부를 위한 보습 크림")),
                List.of(SKINCARE), List.of(), List.of(CREAM_SEED), 30, 50));

        assertThat(found.referenceScores()).containsKey(CREAM_SEED);
        assertThat(found.candidates()).extracting(RecommendCandidate::productId).containsExactly(1L);
    }

    private static ProductSearchDocument doc(long id, String name, String description, long categoryId, boolean soldOut,
                                             double behaviorScore) {
        return new ProductSearchDocument(id, name, description, "팔레트 상점", categoryId, "ON_SALE", soldOut, 20_000, 0,
                0, 0, 0, 0, behaviorScore, id);
    }
}
