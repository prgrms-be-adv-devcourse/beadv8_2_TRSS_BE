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
    // 전체 재색인 보정: 재색인 시작(또는 최신이 아님을 처음 본) 시각보다 이만큼(초) 앞부터 바뀐 상품을 다시 본다 (커밋 지연)
    private final int discountBoundaryOverlapSeconds;
    // 할인 경계 재색인: 지난 실행 시각이 없을 때(앱 기동 후 첫 실행) 돌아보는 구간(분)
    private final int discountBoundaryLookbackMinutes;
    // ES 검색(검색·비슷한 상품·추천)이 접속·응답에 실패하면 이만큼(초) ES를 부르지 않고 DB로 대신한다
    private final int searchSkipAfterFailureSeconds;
    // 인기 검색어: 집계 구간(시간), 반감기(시간), 최소 점수, 보여 주는 개수, 셀 수 있는 검색어 최대 길이, 순위 캐시(초)
    private final int popularKeywordWindowHours;
    private final double popularKeywordHalfLifeHours;
    private final double popularKeywordMinScore;
    private final int popularKeywordLimit;
    private final int popularKeywordMaxLength;
    private final int popularKeywordCacheSeconds;

    public ProductPolicy(
            @Value("${custom.product.image.maxDetailImages}") int maxDetailImages,
            @Value("${custom.product.search.indexBatchSize}") int searchIndexBatchSize,
            @Value("${custom.product.search.discountBoundaryOverlapSeconds}") int discountBoundaryOverlapSeconds,
            @Value("${custom.product.search.discountBoundaryLookbackMinutes}") int discountBoundaryLookbackMinutes,
            @Value("${custom.product.search.skipAfterFailureSeconds}") int searchSkipAfterFailureSeconds,
            @Value("${custom.product.popularKeyword.windowHours}") int popularKeywordWindowHours,
            @Value("${custom.product.popularKeyword.halfLifeHours}") double popularKeywordHalfLifeHours,
            @Value("${custom.product.popularKeyword.minScore}") double popularKeywordMinScore,
            @Value("${custom.product.popularKeyword.limit}") int popularKeywordLimit,
            @Value("${custom.product.popularKeyword.maxLength}") int popularKeywordMaxLength,
            @Value("${custom.product.popularKeyword.cacheSeconds}") int popularKeywordCacheSeconds
    ) {
        this.maxDetailImages = maxDetailImages;
        this.searchIndexBatchSize = searchIndexBatchSize;
        this.discountBoundaryOverlapSeconds = discountBoundaryOverlapSeconds;
        this.discountBoundaryLookbackMinutes = discountBoundaryLookbackMinutes;
        this.searchSkipAfterFailureSeconds = searchSkipAfterFailureSeconds;
        this.popularKeywordWindowHours = popularKeywordWindowHours;
        this.popularKeywordHalfLifeHours = popularKeywordHalfLifeHours;
        this.popularKeywordMinScore = popularKeywordMinScore;
        this.popularKeywordLimit = popularKeywordLimit;
        this.popularKeywordMaxLength = popularKeywordMaxLength;
        this.popularKeywordCacheSeconds = popularKeywordCacheSeconds;
    }
}
