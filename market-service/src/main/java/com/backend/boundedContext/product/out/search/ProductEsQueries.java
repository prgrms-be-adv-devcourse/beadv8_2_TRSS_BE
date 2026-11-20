package com.backend.boundedContext.product.out.search;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MoreLikeThisQuery;
import com.backend.boundedContext.product.domain.ProductSaleStatus;

import java.util.Collection;

/** 상품 검색·비슷한 상품·개인화 추천이 같이 쓰는 ES 조건. 구매자에게 보이는 상품의 기준과 텍스트 유사도 질의를 한곳에 둔다 */
final class ProductEsQueries {

    private ProductEsQueries() {
    }

    /** 판매 중이고 주어진(사용 중인) 소분류의 상품 */
    static BoolQuery.Builder displayable(BoolQuery.Builder b, Collection<Long> categoryIds) {
        return b.filter(f -> f.term(t -> t.field("status").value(ProductSaleStatus.ON_SALE.name())))
                .filter(f -> f.terms(t -> t.field("categoryId").terms(v -> v.value(
                        categoryIds.stream().map(FieldValue::of).toList()))));
    }

    /**
     * 텍스트와 nori 형태소가 겹치는 상품을 찾는 more_like_this (상품명·설명).
     * 상품 수가 적어 문서 빈도 기본값(5)이면 거의 나오지 않으므로 1로 낮춘다
     */
    static MoreLikeThisQuery moreLikeThis(String likeText) {
        return MoreLikeThisQuery.of(mlt -> mlt
                .fields("name", "description")
                .like(l -> l.text(likeText))
                .minTermFreq(1)
                .minDocFreq(1)
                .maxQueryTerms(25)
                .minimumShouldMatch("30%"));
    }
}
