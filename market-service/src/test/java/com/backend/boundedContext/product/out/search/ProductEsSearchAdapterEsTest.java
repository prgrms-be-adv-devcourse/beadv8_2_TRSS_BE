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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * ES 상품 색인·검색 통합 테스트. 로컬 compose의 ES(9.4.5 + nori)가 필요하다.
 * 기본 test에서는 빠지고 {@code ./gradlew :market-service:esTest}로 돌린다.
 * 테스트마다 다른 alias(products_test_...)를 만들고 끝나면 지운다. alias가 없으면 색인에 쓰지 않으므로, 보통은 시작할 때 빈 인덱스와 alias를 만든다.
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
        searchAdapter = new ProductEsSearchAdapter(client, fallback, indexAdapter, alias);
    }

    /** 전체 재색인처럼 새 인덱스를 만들고 alias를 붙인다 */
    private void createAlias() {
        indexAdapter.switchAlias(indexAdapter.createIndex());
    }

    /** ES는 와일드카드로 인덱스를 지울 수 없어서(destructive_requires_name) 만든 인덱스 이름을 찾아 지운다 */
    @AfterEach
    void tearDown() throws Exception {
        List<String> indices = List.copyOf(client.indices()
                .get(g -> g.index(alias + "_*").allowNoIndices(true).ignoreUnavailable(true)).indices().keySet());
        if (!indices.isEmpty()) {
            client.indices().delete(d -> d.index(indices));
        }
        // require_alias가 깨져 ES가 alias 이름으로 인덱스를 자동으로 만든 경우도 지운다
        if (client.indices().exists(e -> e.index(alias)).value()) {
            client.indices().delete(d -> d.index(alias));
        }
        client.close();
    }

    @Test
    @DisplayName("nori: 띄어쓰기·조사가 달라도 형태소로 찾고, 다른 상품은 빼낸다")
    void keywordWithNori() throws Exception {
        createAlias();
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
        createAlias();
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
        createAlias();
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
    @DisplayName("전체 재색인: 새 인덱스를 채운 뒤 alias를 옮기고 이전 인덱스를 지운다. 옮긴 직후부터 새 문서가 검색된다 (refresh 뒤 전환)")
    void rebuildSwitchesAlias() throws Exception {
        createAlias();
        indexAdapter.upsert(List.of(doc(1, "예전 크림", SKINCARE, false, 10_000, 1)));
        String newIndex = indexAdapter.createIndex();
        indexAdapter.bulkIndex(newIndex, List.of(doc(2, "새 크림", SKINCARE, false, 10_000, 2)));
        indexAdapter.switchAlias(newIndex);   // 테스트에서 refresh하지 않고 바로 검색한다

        assertThat(ids(search(null, ProductSort.LATEST, false))).containsExactly(2L);
        assertThat(client.indices().getAlias(g -> g.name(alias)).aliases().keySet()).containsExactly(newIndex);
    }

    @Test
    @DisplayName("alias가 없으면 색인·삭제 요청은 예외이고 인덱스를 만들지 않는다 (빈 인덱스로 검색이 '성공'하지 않게)")
    void noAliasNoWrite() throws Exception {
        assertThat(indexAdapter.aliasExists()).isFalse();

        assertThatThrownBy(() -> indexAdapter.upsert(List.of(doc(1, "크림 A", SKINCARE, false, 10_000, 1))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> indexAdapter.delete(List.of(1L)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(indexAdapter.aliasExists()).isFalse();
        assertThat(client.indices().exists(e -> e.index(alias)).value()).isFalse();
        assertThat(client.indices().get(g -> g.index(alias + "*").allowNoIndices(true).ignoreUnavailable(true)).indices()).isEmpty();
    }

    @Test
    @DisplayName("앱이 떠 있는 동안 인덱스가 지워져도(alias 사라짐) ES가 매핑 없는 인덱스를 자동으로 만들지 않고 쓰기를 거부한다 (require_alias)")
    void requireAliasAfterIndexDeleted() throws Exception {
        createAlias();
        indexAdapter.upsert(List.of(doc(1, "크림 A", SKINCARE, false, 10_000, 1)));   // alias 확인을 마친 상태
        List<String> indices = List.copyOf(client.indices().getAlias(g -> g.name(alias)).aliases().keySet());
        client.indices().delete(d -> d.index(indices));

        assertThatThrownBy(() -> indexAdapter.upsert(List.of(doc(2, "크림 B", SKINCARE, false, 10_000, 2))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(client.indices().exists(e -> e.index(alias)).value()).as("alias 이름으로 인덱스가 자동 생성되지 않는다").isFalse();
    }

    @Test
    @DisplayName("전체 재색인 중 새 인덱스가 지워지면 bulkIndex는 예외이고, 같은 이름으로 매핑 없는 인덱스를 자동으로 만들지 않는다")
    void bulkIndexRefusesDeletedIndex() throws Exception {
        String newIndex = indexAdapter.createIndex();
        client.indices().delete(d -> d.index(newIndex));

        assertThatThrownBy(() -> indexAdapter.bulkIndex(newIndex, List.of(doc(1, "크림 A", SKINCARE, false, 10_000, 1))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(client.indices().exists(e -> e.index(newIndex)).value()).isFalse();
    }

    @Test
    @DisplayName("특가: discounted면 할인율이 0보다 큰 상품만, 할인율순은 할인율 내림차순이고 같으면 최신순이다")
    void discountedFilterAndDiscountRateSort() throws Exception {
        createAlias();
        indexAdapter.upsert(List.of(
                doc(1, "크림 A", SKINCARE, false, 9_000, 10, 1),
                doc(2, "크림 B", SKINCARE, false, 7_000, 30, 2),
                doc(3, "크림 C", SKINCARE, false, 10_000, 0, 3),   // 할인 없음
                doc(4, "크림 D", SKINCARE, false, 9_000, 10, 4)));
        refresh();

        Page<Long> discounted = searchAdapter.searchProductIds(
                new ProductSearchQuery(null, List.of(SKINCARE), false, true, ProductSort.DISCOUNT_RATE), PageRequest.of(0, 20));
        assertThat(ids(discounted)).containsExactly(2L, 4L, 1L);
        assertThat(discounted.getTotalElements()).isEqualTo(3);
        assertThat(ids(search(null, ProductSort.DISCOUNT_RATE, false))).containsExactly(2L, 4L, 1L, 3L);
    }

    @Test
    @DisplayName("상호명으로 검색하면 그 판매자 상품이 나오고, 같은 단어가 상품명에 있는 상품이 상호명만 맞는 상품보다 앞에 온다")
    void businessNameSearch() throws Exception {
        createAlias();
        indexAdapter.upsert(List.of(
                new ProductSearchDocument(1, "수분 크림", "설명", "라벤더 공방", SKINCARE, "ON_SALE", false, 10_000, 0,
                        0, 0, 0, 0, 0, 1),
                new ProductSearchDocument(2, "라벤더 핸드크림", "설명", "다른 상점", SKINCARE, "ON_SALE", false, 10_000, 0,
                        0, 0, 0, 0, 0, 2),
                new ProductSearchDocument(3, "세라믹 머그컵", "설명", "다른 상점", SKINCARE, "ON_SALE", false, 10_000, 0,
                        0, 0, 0, 0, 0, 3)));
        refresh();

        assertThat(ids(search("라벤더", ProductSort.RELEVANCE, false))).containsExactly(2L, 1L);   // 상품명 일치(2)가 앞
        assertThat(ids(search("공방", ProductSort.RELEVANCE, false))).containsExactly(1L);         // 상호명만으로 찾는다
    }

    @Test
    @DisplayName("상호명과 상품명을 섞어 찾으면 둘 다 맞는 상품이 상품명만 맞는 상품보다 앞에 온다 (두 필드 점수를 더한다)")
    void businessNameAndProductName() throws Exception {
        createAlias();
        indexAdapter.upsert(List.of(
                new ProductSearchDocument(1, "수분 크림", "설명", "라벤더 공방", SKINCARE, "ON_SALE", false, 10_000, 0,
                        0, 0, 0, 0, 0, 1),
                new ProductSearchDocument(2, "수분 크림", "설명", "다른 상점", SKINCARE, "ON_SALE", false, 10_000, 0,
                        0, 0, 0, 0, 0, 2),   // 더 최근 상품: 점수가 같으면 앞에 온다
                // "라벤더"를 흔한 상호명 단어로 만든다. 가장 잘 맞는 필드 하나만 보면(기본값) 1·2번이 상품명 점수로 같아져 2번이 앞에 온다
                new ProductSearchDocument(3, "머그컵", "설명", "라벤더 상점", SKINCARE, "ON_SALE", false, 10_000, 0, 0, 0, 0, 0, 0, 3),
                new ProductSearchDocument(4, "텀블러", "설명", "라벤더 상점", SKINCARE, "ON_SALE", false, 10_000, 0, 0, 0, 0, 0, 0, 4),
                new ProductSearchDocument(5, "접시", "설명", "라벤더 상점", SKINCARE, "ON_SALE", false, 10_000, 0, 0, 0, 0, 0, 0, 5),
                new ProductSearchDocument(6, "컵받침", "설명", "라벤더 상점", SKINCARE, "ON_SALE", false, 10_000, 0, 0, 0, 0, 0, 0, 6)));
        refresh();

        assertThat(ids(search("라벤더 크림", ProductSort.RELEVANCE, false))).startsWith(1L, 2L);
    }

    @Test
    @DisplayName("상호명 채우기 대상: 상호명 없이(null) 색인된 문서만 찾고, 빈 문자열(회원에 없는 판매자)은 빼며, 인덱스가 최신이 아니면 찾지 않는다")
    void findIdsWithoutBusinessName() throws Exception {
        assertThat(indexAdapter.findIdsWithoutBusinessName(10)).isEmpty();   // alias를 만들기 전(최신인지 모름)

        createAlias();
        indexAdapter.upsert(List.of(
                new ProductSearchDocument(1, "크림", "설명", null, SKINCARE, "ON_SALE", false, 10_000, 0, 0, 0, 0, 0, 0, 1),
                new ProductSearchDocument(2, "크림", "설명", "", SKINCARE, "ON_SALE", false, 10_000, 0, 0, 0, 0, 0, 0, 2),
                new ProductSearchDocument(3, "크림", "설명", "팔레트 상점", SKINCARE, "ON_SALE", false, 10_000, 0, 0, 0, 0, 0, 0, 3)));
        refresh();

        assertThat(indexAdapter.findIdsWithoutBusinessName(10)).containsExactly(1L);
    }

    @Test
    @DisplayName("행동 지표 정렬: 인기순은 행동 점수, 찜 많은순은 찜 수, 판매량순은 최근 판매량 내림차순이고 같으면 최신순이다")
    void behaviorSorts() throws Exception {
        createAlias();
        indexAdapter.upsert(List.of(
                doc(1, "크림 A", SKINCARE, false, 10_000, 0, 5, 1, 0.30, 1),
                doc(2, "크림 B", SKINCARE, false, 10_000, 0, 1, 9, 0.10, 2),
                doc(3, "크림 C", SKINCARE, false, 10_000, 0, 5, 3, 0.50, 3)));
        refresh();

        assertThat(ids(search(null, ProductSort.POPULAR, false))).containsExactly(3L, 1L, 2L);
        assertThat(ids(search(null, ProductSort.WISH, false))).containsExactly(3L, 1L, 2L);    // 찜 5 같으면 최신순
        assertThat(ids(search(null, ProductSort.SALES, false))).containsExactly(2L, 3L, 1L);
    }

    @Test
    @DisplayName("매핑 버전: alias가 없거나, 지금 인덱스의 매핑 버전이 코드보다 낮으면(_meta 없음 = 1) 최신이 아니다")
    void isCurrentChecksMappingVersion() throws Exception {
        assertThat(indexAdapter.isCurrent()).isFalse();   // alias 없음

        String oldIndex = alias + "_old";   // 버전을 두기 전 매핑(_meta 없음)으로 만든 인덱스
        client.indices().create(c -> c.index(oldIndex).mappings(m -> m.properties("productId", p -> p.long_(l -> l))));
        client.indices().updateAliases(u -> u.actions(a -> a.add(add -> add.index(oldIndex).alias(alias))));
        assertThat(indexAdapter.isCurrent()).isFalse();

        createAlias();   // 지금 코드의 매핑으로 다시 만들고 alias를 옮긴다
        assertThat(indexAdapter.isCurrent()).isTrue();
    }

    @Test
    @DisplayName("매핑 버전이 낮은 이전 인덱스면 ES에 묻지 않고 DB 검색으로 대신한다 (새 필드로 거른 검색이 빈 결과로 성공하지 않게)")
    void outdatedIndexFallsBackToDb() throws Exception {
        // 버전을 두기 전 매핑(_meta 없음, discountRate 없음). 검색·정렬에 쓰는 나머지 필드는 있어 ES 검색 자체는 성공한다
        String oldIndex = alias + "_old";
        client.indices().create(c -> c.index(oldIndex).mappings(m -> m
                .properties("productId", p -> p.long_(l -> l))
                .properties("status", p -> p.keyword(k -> k))
                .properties("categoryId", p -> p.long_(l -> l))
                .properties("soldOut", p -> p.boolean_(bo -> bo))
                .properties("createdAt", p -> p.date(d -> d.format("epoch_millis")))));
        client.index(i -> i.index(oldIndex).id("1").document(Map.of(
                "productId", 1, "status", "ON_SALE", "categoryId", SKINCARE, "soldOut", false, "createdAt", 1)));
        client.indices().refresh(r -> r.index(oldIndex));
        client.indices().updateAliases(u -> u.actions(a -> a.add(add -> add.index(oldIndex).alias(alias))));
        assertThat(indexAdapter.isCurrent()).isFalse();   // 복구 스케줄러의 확인
        Page<Long> fromDb = new PageImpl<>(List.of(7L));
        given(fallback.searchProductIds(any(), any())).willReturn(fromDb);

        Page<Long> result = searchAdapter.searchProductIds(
                new ProductSearchQuery(null, List.of(SKINCARE), false, true, ProductSort.LATEST), PageRequest.of(0, 20));

        assertThat(result).isSameAs(fromDb);
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
                new ProductSearchQuery(keyword, categoryIds, excludeSoldOut, false, sort), PageRequest.of(0, 20));
    }

    private static List<Long> ids(Page<Long> page) {
        return page.getContent();
    }

    private void refresh() throws Exception {
        client.indices().refresh(r -> r.index(alias));
    }

    private static ProductSearchDocument doc(long id, String name, long categoryId, boolean soldOut,
                                             long effectivePrice, long createdAt) {
        return doc(id, name, categoryId, soldOut, effectivePrice, 0, createdAt);
    }

    private static ProductSearchDocument doc(long id, String name, long categoryId, boolean soldOut,
                                             long effectivePrice, int discountRate, long createdAt) {
        return doc(id, name, categoryId, soldOut, effectivePrice, discountRate, 0, 0, 0, createdAt);
    }

    private static ProductSearchDocument doc(long id, String name, long categoryId, boolean soldOut, long effectivePrice,
                                             int discountRate, long wishCount, long salesCount30d, double behaviorScore,
                                             long createdAt) {
        return new ProductSearchDocument(id, name, "설명", "팔레트 상점", categoryId, "ON_SALE", soldOut, effectivePrice, discountRate,
                wishCount, 0, 0, salesCount30d, behaviorScore, createdAt);
    }
}
