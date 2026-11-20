package com.backend.boundedContext.product.out;

import com.backend.boundedContext.product.domain.RecommendCandidates;

/**
 * 개인화 추천 후보 조회. 후보와 점수 재료(기준 상품별 텍스트 점수와 기준값)만 돌려주고, 순서는 app이 정한다.
 * ES 구현이 기본이고, ES를 쓸 수 없으면 DB 구현({@link ProductJpaRecommendAdapter})으로 대신한다.
 */
public interface ProductRecommendPort {

    RecommendCandidates findCandidates(ProductRecommendQuery query);
}
