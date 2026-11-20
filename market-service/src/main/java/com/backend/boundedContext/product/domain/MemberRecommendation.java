package com.backend.boundedContext.product.domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 미리 계산해 저장해 두는 회원 추천 (상품 ID와 이유만, 화면 값은 읽을 때 DB로 채운다)
 *
 * @param personalized 회원 행동으로 계산했는지. false면 기준 상품이 없어 읽을 때 인기순으로 대신한다
 * @param generatedAt  계산한 시각
 * @param forYou       회원님을 위한 추천 (점수 순)
 * @param sections     기준 상품별 추천
 */
public record MemberRecommendation(
        boolean personalized,
        LocalDateTime generatedAt,
        List<Item> forYou,
        List<Section> sections
) {

    public static MemberRecommendation notPersonalized(LocalDateTime now) {
        return new MemberRecommendation(false, now, List.of(), List.of());
    }

    /**
     * @param productId       추천 상품 ID
     * @param reasonBasis     이유가 된 기준 상품의 출처. 이유가 없으면 null
     * @param reasonProductId 이유가 된 기준 상품 ID. 없으면 null
     */
    public record Item(long productId, RecommendBasis reasonBasis, Long reasonProductId) {
    }

    /**
     * @param basis         기준 상품의 출처
     * @param seedProductId 기준 상품 ID
     * @param productIds    그 기준 상품과 비슷한 상품 (점수 순)
     */
    public record Section(RecommendBasis basis, long seedProductId, List<Long> productIds) {
    }
}
