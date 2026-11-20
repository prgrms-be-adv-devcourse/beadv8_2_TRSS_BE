package com.backend.boundedContext.product.domain;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 비슷한 상품 추천 설정 (custom.product.similar.*).
 * 점수 = textWeight·텍스트 유사도 + categoryWeight·같은 소분류 + priceWeight·가격 근접도 + behaviorWeight·행동 점수 (각 0~1)
 */
@Component
@Getter
public class ProductSimilarPolicy {
    private final double textWeight;
    private final double categoryWeight;
    private final double priceWeight;
    private final double behaviorWeight;
    /** 텍스트 유사 후보와 같은 소분류 인기 후보를 각각 이만큼까지 가져온다 */
    private final int candidateSize;

    public ProductSimilarPolicy(
            @Value("${custom.product.similar.textWeight}") double textWeight,
            @Value("${custom.product.similar.categoryWeight}") double categoryWeight,
            @Value("${custom.product.similar.priceWeight}") double priceWeight,
            @Value("${custom.product.similar.behaviorWeight}") double behaviorWeight,
            @Value("${custom.product.similar.candidateSize}") int candidateSize
    ) {
        if (Math.abs(textWeight + categoryWeight + priceWeight + behaviorWeight - 1.0) > 1e-9) {
            throw new IllegalStateException("비슷한 상품 가중치의 합은 1이어야 합니다. text=" + textWeight + ", category="
                    + categoryWeight + ", price=" + priceWeight + ", behavior=" + behaviorWeight);
        }
        if (textWeight < 0 || categoryWeight < 0 || priceWeight < 0 || behaviorWeight < 0) {
            throw new IllegalStateException("비슷한 상품 가중치는 0 이상이어야 합니다.");
        }
        if (candidateSize < 1) {
            throw new IllegalStateException("비슷한 상품 후보 수는 1 이상이어야 합니다.");
        }
        this.textWeight = textWeight;
        this.categoryWeight = categoryWeight;
        this.priceWeight = priceWeight;
        this.behaviorWeight = behaviorWeight;
        this.candidateSize = candidateSize;
    }
}
