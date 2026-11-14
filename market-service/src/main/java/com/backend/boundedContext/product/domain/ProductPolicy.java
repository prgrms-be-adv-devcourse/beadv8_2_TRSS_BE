package com.backend.boundedContext.product.domain;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 상품 컨텍스트 정책 값
 */
@Component
@Getter
public class ProductPolicy {
    // 상품 하나에 올릴 수 있는 상세 이미지 최대 개수 (대표 이미지는 별도로 1개)
    private final int maxDetailImages;
    // 검색 전체 재색인이 한 번에 읽어 bulk로 보내는 상품 수
    private final int searchIndexBatchSize;
    // 할인 경계 재색인: 지난 성공 실행 시각보다 이만큼(초) 앞부터 다시 본다
    private final int discountBoundaryOverlapSeconds;
    // 할인 경계 재색인: 지난 실행 시각이 없을 때(앱 기동 후 첫 실행) 돌아보는 구간(분)
    private final int discountBoundaryLookbackMinutes;

    public ProductPolicy(
            @Value("${custom.product.image.maxDetailImages}") int maxDetailImages,
            @Value("${custom.product.search.indexBatchSize}") int searchIndexBatchSize,
            @Value("${custom.product.search.discountBoundaryOverlapSeconds}") int discountBoundaryOverlapSeconds,
            @Value("${custom.product.search.discountBoundaryLookbackMinutes}") int discountBoundaryLookbackMinutes
    ) {
        this.maxDetailImages = maxDetailImages;
        this.searchIndexBatchSize = searchIndexBatchSize;
        this.discountBoundaryOverlapSeconds = discountBoundaryOverlapSeconds;
        this.discountBoundaryLookbackMinutes = discountBoundaryLookbackMinutes;
    }
}
