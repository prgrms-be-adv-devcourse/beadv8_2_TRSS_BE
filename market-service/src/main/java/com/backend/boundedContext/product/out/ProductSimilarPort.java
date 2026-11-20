package com.backend.boundedContext.product.out;

import com.backend.boundedContext.product.domain.ProductSimilarCandidates;

import java.util.List;

/**
 * 비슷한 상품 후보 조회. 후보와 점수 재료만 돌려주고, 순서는 app이 정한다
 * (텍스트 점수는 질의마다 크기가 달라 다른 항목과 합치기 전에 맞춰야 하기 때문이다).
 * ES 구현이 기본이고, ES를 쓸 수 없으면 DB 구현({@link ProductJpaSimilarAdapter})으로 대신한다.
 */
public interface ProductSimilarPort {

    ProductSimilarCandidates findCandidates(ProductSimilarQuery query);

    /** 여러 기준 상품의 후보를 한꺼번에 (개인화 추천의 기준 상품별 섹션). ES 구현은 요청 한 번으로 보낸다 */
    default List<ProductSimilarCandidates> findAllCandidates(List<ProductSimilarQuery> queries) {
        return queries.stream().map(this::findCandidates).toList();
    }
}
