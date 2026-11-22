package com.backend.boundedContext.product.out.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.MsearchResponse;
import co.elastic.clients.elasticsearch.core.msearch.MultiSearchResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.backend.boundedContext.product.domain.RecommendCandidate;
import com.backend.boundedContext.product.domain.RecommendCandidates;
import com.backend.boundedContext.product.out.ProductJpaRecommendAdapter;
import com.backend.boundedContext.product.out.ProductRecommendPort;
import com.backend.boundedContext.product.out.ProductRecommendQuery;
import com.backend.boundedContext.product.out.ProductRecommendQuery.SeedText;
import com.backend.boundedContext.product.out.search.ProductEsSimilarAdapter.CandidateSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ES로 개인화 추천 후보를 찾는다. 기준 상품마다 more_like_this를 따로 돌려(추천 이유를 알려고) msearch 한 번으로 보내고,
 * 선호 소분류의 행동 점수 상위 상품을 같은 요청에 더한다.
 * 기준 상품마다 그 기준 상품 자신도 함께 찾아(품절·소분류 사용 중지여도) 그 점수를 텍스트 기준값으로 준다.
 * 인덱스가 최신이 아니거나 ES가 실패하면(한 질의라도) DB 구현({@link ProductJpaRecommendAdapter})으로 대신한다.
 */
@Slf4j
@Primary
@Component
@ConditionalOnProperty(name = "custom.product.search.engine", havingValue = "es")
public class ProductEsRecommendAdapter implements ProductRecommendPort {
    private static final List<String> SOURCE_FIELDS = List.of("categoryId", "effectivePrice", "behaviorScore");

    private final ElasticsearchClient client;
    private final ProductJpaRecommendAdapter fallback;
    private final ProductEsSearchGuard guard;
    private final String alias;

    public ProductEsRecommendAdapter(ElasticsearchClient client, ProductJpaRecommendAdapter fallback,
                                     ProductEsSearchGuard guard,
                                     @Value("${custom.product.search.indexAlias}") String alias) {
        this.client = client;
        this.fallback = fallback;
        this.guard = guard;
        this.alias = alias;
    }

    @Override
    public RecommendCandidates findCandidates(ProductRecommendQuery query) {
        // 인덱스가 최신이 아니거나 ES 접속·응답이 방금 실패했으면 DB로 대신한다 (ProductEsSearchGuard)
        if (!guard.canSearch()) {
            return fallback.findCandidates(query).asDegraded();
        }
        try {
            RecommendCandidates result = search(query);
            guard.onSuccess();
            return result;
        } catch (Exception e) {
            log.warn("ES 개인화 추천 후보 조회에 실패해 DB로 대신합니다. seeds={}", query.seeds().size(), e);
            guard.onFailure(e);
            return fallback.findCandidates(query).asDegraded();
        }
    }

    private RecommendCandidates search(ProductRecommendQuery query) throws IOException {
        List<SeedText> textSeeds = query.seeds().stream()
                .filter(seed -> seed.likeText() != null && !seed.likeText().isBlank())
                .toList();
        List<Search> searches = new ArrayList<>();
        textSeeds.forEach(seed -> searches.add(new Search(textQuery(query, seed), query.candidatePerSeed() + 1, List.of())));
        boolean withPreferred = !query.preferredCategoryIds().isEmpty();
        if (withPreferred) {
            searches.add(new Search(preferredQuery(query), query.preferredSize(),
                    List.of(field("behaviorScore"), field("createdAt"), field("productId"))));
        }
        if (searches.isEmpty()) return RecommendCandidates.withoutText(List.of());

        MsearchResponse<CandidateSource> response = client.msearch(m -> {
            searches.forEach(search -> m.searches(s -> s
                    .header(h -> h.index(alias))
                    .body(b -> b.query(search.query())
                            .size(search.size())
                            .sort(search.sort())
                            .source(src -> src.filter(f -> f.includes(SOURCE_FIELDS))))));
            return m;
        }, CandidateSource.class);

        Map<Long, Builder> candidates = new LinkedHashMap<>();
        Map<Long, Double> references = new HashMap<>();
        List<MultiSearchResponseItem<CandidateSource>> items = response.responses();
        for (int i = 0; i < textSeeds.size(); i++) {
            long seedId = textSeeds.get(i).productId();
            for (Hit<CandidateSource> hit : ProductEsSimilarAdapter.hits(items.get(i))) {
                long productId = Long.parseLong(hit.id());
                double score = hit.score() == null ? 0 : hit.score();
                if (productId == seedId) {
                    references.put(seedId, score);
                } else {
                    candidates.computeIfAbsent(productId, id -> new Builder(id, hit.source())).textScores.put(seedId, score);
                }
            }
        }
        if (withPreferred) {
            ProductEsSimilarAdapter.hits(items.getLast()).forEach(hit ->
                    candidates.computeIfAbsent(Long.parseLong(hit.id()), id -> new Builder(id, hit.source())));
        }
        return new RecommendCandidates(candidates.values().stream().map(Builder::build).toList(), references);
    }

    /**
     * 기준 상품 텍스트와 형태소가 겹치는 상품: 보이는(판매 중·사용 중 소분류·품절 아님·뺄 상품 아님) 상품이거나 기준 상품 자신.
     * 기준 상품 자신은 품절이거나 소분류가 사용 중지돼도 찾는다. 그 점수가 텍스트 기준값이라, 빠지면 약한 일치가 만점이 된다
     */
    private static Query textQuery(ProductRecommendQuery query, SeedText seed) {
        String seedId = String.valueOf(seed.productId());
        List<String> excluded = query.excludeIds().stream()
                .filter(id -> id != seed.productId()).map(String::valueOf).toList();
        return Query.of(q -> q.bool(b -> b
                .filter(f -> f.bool(visibleOrSeed -> visibleOrSeed
                        .should(s -> s.bool(visible -> {
                            ProductEsQueries.displayable(visible, query.categoryIds())
                                    .filter(v -> v.term(t -> t.field("soldOut").value(false)));
                            if (!excluded.isEmpty()) visible.mustNot(m -> m.ids(i -> i.values(excluded)));
                            return visible;
                        }))
                        .should(s -> s.ids(i -> i.values(seedId)))))
                .must(m -> m.moreLikeThis(ProductEsQueries.moreLikeThis(seed.likeText())))));
    }

    /** 선호 소분류의 품절 아닌 행동 점수 상위 상품 (뺄 상품 제외) */
    private static Query preferredQuery(ProductRecommendQuery query) {
        List<String> excluded = query.excludeIds().stream().map(String::valueOf).toList();
        return Query.of(q -> q.bool(b -> {
            ProductEsQueries.displayable(b, query.categoryIds())
                    .filter(f -> f.term(t -> t.field("soldOut").value(false)))
                    .filter(f -> f.terms(t -> t.field("categoryId").terms(v -> v.value(
                            query.preferredCategoryIds().stream().map(FieldValue::of).toList()))));
            if (!excluded.isEmpty()) b.mustNot(m -> m.ids(i -> i.values(excluded)));
            return b;
        }));
    }

    private static SortOptions field(String name) {
        return SortOptions.of(o -> o.field(f -> f.field(name).order(SortOrder.Desc)));
    }

    private record Search(Query query, int size, List<SortOptions> sort) {
    }

    private static final class Builder {
        private final long productId;
        private final CandidateSource source;
        private final Map<Long, Double> textScores = new HashMap<>();

        private Builder(long productId, CandidateSource source) {
            this.productId = productId;
            this.source = source;
        }

        private RecommendCandidate build() {
            return new RecommendCandidate(productId, source.categoryId(), source.effectivePrice(), source.behaviorScore(),
                    Map.copyOf(textScores));
        }
    }
}
