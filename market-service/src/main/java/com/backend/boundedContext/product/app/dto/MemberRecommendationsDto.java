package com.backend.boundedContext.product.app.dto;

import java.util.List;

/**
 * 회원 추천
 *
 * @param personalized 회원 행동으로 추천했는지. 로그인하지 않았거나 행동 이력이 없으면 false이고 forYou는 인기순이다
 * @param forYou       회원님을 위한 추천
 * @param sections     기준 상품별 섹션. personalized가 false면 빈 목록. 같은 상품은 forYou나 앞 섹션에 한 번만 나온다
 */
public record MemberRecommendationsDto(
        boolean personalized,
        List<RecommendedProductDto> forYou,
        List<RecommendationSectionDto> sections
) {
}
