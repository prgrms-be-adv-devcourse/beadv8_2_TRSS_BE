package com.backend.boundedContext.product.out.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.MsearchResponse;
import co.elastic.clients.elasticsearch.core.msearch.MultiSearchResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.backend.boundedContext.product.domain.ProductSimilarCandidate;
import com.backend.boundedContext.product.domain.ProductSimilarCandidates;
import com.backend.boundedContext.product.out.ProductJpaSimilarAdapter;
import com.backend.boundedContext.product.out.ProductSimilarPort;
import com.backend.boundedContext.product.out.ProductSimilarQuery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ES로 비슷한 상품 후보를 찾는다. 기준 상품마다 두 가지를 합친다.
 * <ul>
 *   <li>텍스트 후보: 기준 상품의 상품명·설명으로 more_like_this를 돌려 nori 형태소가 겹치는 상품 (점수 = 텍스트 유사도)</li>
 *   <li>같은 소분류 후보: 텍스트가 짧거나 겹치는 말이 없어도 결과가 비지 않게, 같은 소분류의 행동 점수 상위 상품</li>
 * </ul>
 * 기준 상품 여러 개도 msearch 한 번으로 보낸다 (개인화 추천의 기준 상품별 섹션).
 * 인덱스가 최신이 아니거나 ES가 실패하면(한 질의라도) DB 구현({@link ProductJpaSimilarAdapter})으로 대신한다.
 */
@Slf4j
@Primary
@Component
@ConditionalOnProperty(name = "custom.product.search.engine", havingValue = "es")
public class ProductEsSimilarAdapter implements ProductSimilarPort {
    /** 후보의 점수 재료만 읽는다 */
    private static final List<String> SOURCE_FIELDS = List.of("categoryId", "effectivePrice", "behaviorScore");

    private final ElasticsearchClient client;
    private final ProductJpaSimilarAdapter fallback;
    private final ProductEsIndexAdapter indexAdapter;
    private final String alias;

    public ProductEsSimilarAdapter(ElasticsearchClient client, ProductJpaSimilarAdapter fallback, ProductEsIndexAdapter indexAdapter,
                                   @Value("${custom.product.search.indexAlias}") String alias) {
        this.client = client;
        this.fallback = fallback;
        this.indexAdapter = indexAdapter;
        this.alias = alias;
    }

    @Override
    public ProductSimilarCandidates findCandidates(ProductSimilarQuery query) {
        return findAllCandidates(List.of(query)).getFirst();
    }

    @Override
    public List<ProductSimilarCandidates> findAllCandidates(List<ProductSimilarQuery> queries) {
        if (queries.isEmpty()) return List.of();
        // 매핑 버전이 낮은 이전 인덱스에는 설명 필드가 없어 텍스트 후보가 오류 없이 비므로 DB로 대신한다
        if (!indexAdapter.isKnownCurrent()) {
            return degraded(queries);
        }
        try {
            return search(queries);
        } catch (Exception e) {
            log.warn("ES 비슷한 상품 조회에 실패해 DB로 대신합니다. productIds={}",
                    queries.stream().map(ProductSimilarQuery::productId).toList(), e);
            return degraded(queries);
        }
    }

    private List<ProductSimilarCandidates> degraded(List<ProductSimilarQuery> queries) {
        return queries.stream().map(query -> fallback.findCandidates(query).asDegraded()).toList();
    }

    /** 기준 상품마다 [텍스트 질의(텍스트가 있을 때), 같은 소분류 질의]를 차례로 넣어 한 번에 보낸다 */
    private List<ProductSimilarCandidates> search(List<ProductSimilarQuery> queries) throws IOException {
        List<Search> searches = new ArrayList<>();
        for (ProductSimilarQuery query : queries) {
            if (hasText(query)) searches.add(new Search(textQuery(query), query.candidateSize() + 1, List.of()));
            searches.add(new Search(sameCategoryQuery(query), query.candidateSize(),
                    List.of(field("behaviorScore"), field("createdAt"), field("productId"))));
        }
        MsearchResponse<CandidateSource> response = client.msearch(m -> {
            searches.forEach(search -> m.searches(s -> s
                    .header(h -> h.index(alias))
                    .body(b -> b.query(search.query())
                            .size(search.size())
                            .sort(search.sort())
                            .source(src -> src.filter(f -> f.includes(SOURCE_FIELDS))))));
            return m;
        }, CandidateSource.class);

        List<MultiSearchResponseItem<CandidateSource>> items = response.responses();
        List<ProductSimilarCandidates> results = new ArrayList<>();
        int next = 0;
        for (ProductSimilarQuery query : queries) {
            Map<Long, ProductSimilarCandidate> candidates = new LinkedHashMap<>();
            double referenceTextScore = 0;
            if (hasText(query)) {
                for (Hit<CandidateSource> hit : hits(items.get(next++))) {
                    ProductSimilarCandidate candidate = toCandidate(hit, true);
                    if (candidate.productId() == query.productId()) {
                        referenceTextScore = candidate.textScore();   // 기준 상품 자기 자신 = 완전 일치의 점수
                    } else {
                        candidates.put(candidate.productId(), candidate);
                    }
                }
            }
            hits(items.get(next++)).forEach(hit -> {
                ProductSimilarCandidate candidate = toCandidate(hit, false);
                candidates.putIfAbsent(candidate.productId(), candidate);
            });
            results.add(new ProductSimilarCandidates(List.copyOf(candidates.values()), referenceTextScore));
        }
        return results;
    }

    private static boolean hasText(ProductSimilarQuery query) {
        return query.likeText() != null && !query.likeText().isBlank();
    }

    /**
     * 기준 상품 텍스트와 형태소가 겹치는 상품. 기준 문서(_id)가 아니라 DB에서 읽은 텍스트로 찾는다 (색인이 늦어도 지금 내용으로 찾는다).
     * 기준 상품은 빼지 않고 함께 찾아(품절이어도) 그 점수를 텍스트 점수의 기준값으로 쓴다.
     * 상품 수가 적어 문서 빈도 기본값(5)이면 거의 나오지 않으므로 1로 낮춘다.
     */
    private static Query textQuery(ProductSimilarQuery query) {
        String baseId = String.valueOf(query.productId());
        return Query.of(q -> q.bool(b -> ProductEsQueries.displayable(b, query.categoryIds())
                .filter(f -> f.bool(inStockOrBase -> inStockOrBase
                        .should(s -> s.term(t -> t.field("soldOut").value(false)))
                        .should(s -> s.ids(i -> i.values(baseId)))))
                .must(m -> m.moreLikeThis(ProductEsQueries.moreLikeThis(query.likeText())))));
    }

    /** 같은 소분류의 품절 아닌 행동 점수 상위 상품 (기준 상품 제외, 텍스트 점수 0) */
    private static Query sameCategoryQuery(ProductSimilarQuery query) {
        return Query.of(q -> q.bool(b -> ProductEsQueries.displayable(b, query.categoryIds())
                .filter(f -> f.term(t -> t.field("soldOut").value(false)))
                .filter(f -> f.term(t -> t.field("categoryId").value(query.categoryId())))
                .mustNot(m -> m.ids(i -> i.values(String.valueOf(query.productId()))))));
    }

    /** 실패한 질의가 하나라도 있으면 예외 (결과를 반쯤 쓰지 않고 DB로 대신한다) */
    static List<Hit<CandidateSource>> hits(MultiSearchResponseItem<CandidateSource> item) {
        if (item.isFailure()) {
            throw new SearchIndexUnavailableException("ES 질의가 실패했습니다. " + item.failure().error().reason());
        }
        return item.result().hits().hits().stream().filter(hit -> hit.source() != null).toList();
    }

    private static ProductSimilarCandidate toCandidate(Hit<CandidateSource> hit, boolean useScore) {
        CandidateSource source = hit.source();
        double textScore = useScore && hit.score() != null ? hit.score() : 0;
        return new ProductSimilarCandidate(Long.parseLong(hit.id()), source.categoryId(), source.effectivePrice(),
                source.behaviorScore(), textScore);
    }

    private static SortOptions field(String name) {
        return SortOptions.of(o -> o.field(f -> f.field(name).order(SortOrder.Desc)));
    }

    private record Search(Query query, int size, List<SortOptions> sort) {
    }

    /** 후보 문서에서 읽는 필드 */
    record CandidateSource(long categoryId, long effectivePrice, double behaviorScore) {
    }
}
