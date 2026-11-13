package com.backend.boundedContext.product.out.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.TotalHits;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import com.backend.boundedContext.product.domain.ProductSort;
import com.backend.boundedContext.product.out.ProductJpaSearchAdapter;
import com.backend.boundedContext.product.out.ProductSearchPort;
import com.backend.boundedContext.product.out.ProductSearchQuery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ES로 상품을 검색한다. 상품명은 nori 형태소로 찾고, 가격순은 색인해 둔 지금 기본가(effectivePrice, 할인 중이면 할인가) 기준이다.
 * ES가 꺼져 있거나 검색이 실패하면 DB 검색({@link ProductJpaSearchAdapter})으로 대신한다. 이때 가격순은 정가 기준이다.
 */
@Slf4j
@Primary
@Component
@ConditionalOnProperty(name = "custom.product.search.engine", havingValue = "es")
public class ProductEsSearchAdapter implements ProductSearchPort {
    private static final String PRODUCT_ID = "productId";

    private final ElasticsearchClient client;
    private final ProductJpaSearchAdapter fallback;
    private final String alias;

    public ProductEsSearchAdapter(ElasticsearchClient client, ProductJpaSearchAdapter fallback,
                                  @Value("${custom.product.search.indexAlias}") String alias) {
        this.client = client;
        this.fallback = fallback;
        this.alias = alias;
    }

    @Override
    public Page<Long> searchProductIds(ProductSearchQuery query, Pageable pageable) {
        try {
            return search(query, pageable);
        } catch (Exception e) {
            log.warn("ES 상품 검색에 실패해 DB 검색으로 대신합니다. keyword={}, sort={}", query.keyword(), query.sort(), e);
            return fallback.searchProductIds(query, pageable);
        }
    }

    private Page<Long> search(ProductSearchQuery query, Pageable pageable) throws Exception {
        SearchResponse<Void> response = client.search(s -> s
                        .index(alias)
                        .query(q -> q.bool(b -> toQuery(b, query)))
                        .sort(toSort(query.sort()))
                        .from((int) pageable.getOffset())
                        .size(pageable.getPageSize())
                        .trackTotalHits(t -> t.enabled(true))
                        .source(src -> src.fetch(false)),
                Void.class);

        List<Long> productIds = response.hits().hits().stream()
                .map(Hit::id)
                .map(Long::valueOf)
                .toList();
        TotalHits total = response.hits().total();
        return new PageImpl<>(productIds, pageable, total == null ? productIds.size() : total.value());
    }

    private static BoolQuery.Builder toQuery(BoolQuery.Builder b, ProductSearchQuery query) {
        b.filter(f -> f.term(t -> t.field("status").value(ProductSaleStatus.ON_SALE.name())));
        b.filter(f -> f.terms(t -> t.field("categoryId").terms(v -> v.value(
                query.categoryIds().stream().map(FieldValue::of).toList()))));
        if (query.excludeSoldOut()) {
            b.filter(f -> f.term(t -> t.field("soldOut").value(false)));
        }
        if (query.keyword() != null) {
            b.must(m -> m.match(mt -> mt.field("name").query(query.keyword())));
        }
        return b;
    }

    /** 값이 같으면 상품 ID 내림차순으로 이어 정렬해 페이지 사이에 중복·누락이 없게 한다 */
    private static List<SortOptions> toSort(ProductSort sort) {
        SortOptions byId = field(PRODUCT_ID, SortOrder.Desc);
        SortOptions latest = field("createdAt", SortOrder.Desc);
        return switch (sort) {
            case RELEVANCE -> List.of(SortOptions.of(o -> o.score(sc -> sc.order(SortOrder.Desc))), latest, byId);
            case LATEST -> List.of(latest, byId);
            case PRICE_ASC -> List.of(field("effectivePrice", SortOrder.Asc), byId);
            case PRICE_DESC -> List.of(field("effectivePrice", SortOrder.Desc), byId);
        };
    }

    private static SortOptions field(String name, SortOrder order) {
        return SortOptions.of(o -> o.field(f -> f.field(name).order(order)));
    }
}
