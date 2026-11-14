package com.backend.boundedContext.product.out.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.backend.boundedContext.product.domain.ProductSort;
import com.backend.boundedContext.product.out.ProductJpaSearchAdapter;
import com.backend.boundedContext.product.out.ProductSearchQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * ES 상품 색인·검색 통합 테스트. 로컬 compose의 ES(9.4.5 + nori)가 필요하다.
 * 기본 test에서는 빠지고 {@code ./gradlew :market-service:esTest}로 돌린다.
 * 테스트마다 다른 alias(products_test_...)를 만들고 끝나면 지운다.
 */
@Tag("es")
class ProductEsSearchAdapterEsTest {
    private static final long SKINCARE = 10L;
    private static final long MAKEUP = 20L;

    private ElasticsearchClient client;
    private ProductJpaSearchAdapter fallback;
    private ProductEsIndexAdapter indexAdapter;
    private ProductEsSearchAdapter searchAdapter;
    private String alias;

    @BeforeEach
    void setUp() {
        client = ElasticsearchClient.of(b -> b.host("http://localhost:9200").usernameAndPassword("elastic", "elastic"));
        alias = "products_test_" + UUID.randomUUID().toString().substring(0, 8);
        fallback = mock(ProductJpaSearchAdapter.class);
        indexAdapter = new ProductEsIndexAdapter(client, alias);
        searchAdapter = new ProductEsSearchAdapter(client, fallback, alias);
    }

    /** ES는 와일드카드로 인덱스를 지울 수 없어서(destructive_requires_name) 만든 인덱스 이름을 찾아 지운다 */
    @AfterEach
    void tearDown() throws Exception {
        List<String> indices = List.copyOf(client.indices()
                .get(g -> g.index(alias + "_*").allowNoIndices(true).ignoreUnavailable(true)).indices().keySet());
        if (!indices.isEmpty()) {
            client.indices().delete(d -> d.index(indices));
        }
        client.close();
    }

    @Test
    @DisplayName("nori: 띄어쓰기·조사가 달라도 형태소로 찾고, 다른 상품은 빼낸다")
    void keywordWithNori() throws Exception {
        indexAdapter.upsert(List.of(
                doc(1, "촉촉한 수분크림 50ml", SKINCARE, false, 30_000, 1),
                doc(2, "수분 가득 크림을", SKINCARE, false, 20_000, 2),
                doc(3, "세라믹 머그컵", SKINCARE, false, 10_000, 3)));
        refresh();

        assertThat(ids(search("크림", ProductSort.RELEVANCE, false))).containsExactlyInAnyOrder(1L, 2L);
        assertThat(ids(search("머그컵", ProductSort.RELEVANCE, false))).containsExactly(3L);
    }

    @Test
    @DisplayName("가격순은 effectivePrice(할인가) 기준이고, 값이 같으면 상품 ID 내림차순이다")
    void priceSortUsesEffectivePrice() throws Exception {
        indexAdapter.upsert(List.of(
                doc(1, "크림 A", SKINCARE, false, 15_000, 1),   // 정가 20,000 → 할인가 15,000
                doc(2, "크림 B", SKINCARE, false, 18_000, 2),
                doc(3, "크림 C", SKINCARE, false, 15_000, 3)));
        refresh();

        assertThat(ids(search(null, ProductSort.PRICE_ASC, false))).containsExactly(3L, 1L, 2L);
        assertThat(ids(search(null, ProductSort.PRICE_DESC, false))).containsExactly(2L, 3L, 1L);
        assertThat(ids(search(null, ProductSort.LATEST, false))).containsExactly(3L, 2L, 1L);
    }

    @Test
    @DisplayName("카테고리·품절 제외 필터를 적용하고, 지운 문서는 검색되지 않는다")
    void filtersAndDelete() throws Exception {
        indexAdapter.upsert(List.of(
                doc(1, "크림 A", SKINCARE, false, 10_000, 1),
                doc(2, "크림 B", SKINCARE, true, 10_000, 2),
                doc(3, "립 C", MAKEUP, false, 10_000, 3),
                doc(4, "크림 D", SKINCARE, false, 10_000, 4)));
        indexAdapter.delete(List.of(4L, 999L));   // 없는 문서는 무시
        refresh();

        assertThat(ids(search(null, List.of(SKINCARE), ProductSort.LATEST, true))).containsExactly(1L);
        assertThat(ids(search(null, List.of(SKINCARE), ProductSort.LATEST, false))).containsExactly(2L, 1L);
        assertThat(ids(search(null, List.of(SKINCARE, MAKEUP), ProductSort.LATEST, true))).containsExactly(3L, 1L);
    }

    @Test
    @DisplayName("전체 재색인: 새 인덱스를 채운 뒤 alias를 옮기고 이전 인덱스를 지운다")
    void rebuildSwitchesAlias() throws Exception {
        indexAdapter.upsert(List.of(doc(1, "예전 크림", SKINCARE, false, 10_000, 1)));
        String newIndex = indexAdapter.createIndex();
        indexAdapter.bulkIndex(newIndex, List.of(doc(2, "새 크림", SKINCARE, false, 10_000, 2)));
        indexAdapter.switchAlias(newIndex);
        refresh();

        assertThat(ids(search(null, ProductSort.LATEST, false))).containsExactly(2L);
        assertThat(client.indices().getAlias(g -> g.name(alias)).aliases().keySet()).containsExactly(newIndex);
    }

    @Test
    @DisplayName("ES 검색이 실패하면(인덱스 없음) DB 검색으로 대신한다")
    void fallbackToJpa() {
        Page<Long> fromDb = new PageImpl<>(List.of(7L));
        given(fallback.searchProductIds(any(), any())).willReturn(fromDb);

        Page<Long> result = search("크림", ProductSort.PRICE_ASC, false);

        assertThat(result).isSameAs(fromDb);
        verify(fallback).searchProductIds(any(), any());
    }

    private Page<Long> search(String keyword, ProductSort sort, boolean excludeSoldOut) {
        return search(keyword, List.of(SKINCARE, MAKEUP), sort, excludeSoldOut);
    }

    private Page<Long> search(String keyword, List<Long> categoryIds, ProductSort sort, boolean excludeSoldOut) {
        return searchAdapter.searchProductIds(
                new ProductSearchQuery(keyword, categoryIds, excludeSoldOut, sort), PageRequest.of(0, 20));
    }

    private static List<Long> ids(Page<Long> page) {
        return page.getContent();
    }

    private void refresh() throws Exception {
        client.indices().refresh(r -> r.index(alias));
    }

    private static ProductSearchDocument doc(long id, String name, long categoryId, boolean soldOut,
                                             long effectivePrice, long createdAt) {
        return new ProductSearchDocument(id, name, categoryId, "ON_SALE", soldOut, effectivePrice, createdAt);
    }
}
