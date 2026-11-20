package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.ProductSummaryDto;
import com.backend.boundedContext.product.domain.ProductRecommendPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ProductGetRecentRecommendationsUseCase {
    /** 최대 개수 */
    static final int MAX_SIZE = 50;

    private final ProductRecommendPolicy policy;
    private final ProductRecommendSupport productRecommendSupport;

    /**
     * 최근 본 상품 기반 추천. 프론트가 localStorage의 최근 본 상품 ID를 최근 순으로 보낸다.
     * 앞의 recentViewMax개만 받고, 없는 상품 ID는 무시한다. 요청할 때 계산하고 저장하지 않는다.
     * 최근 본 상품은 결과에서 빼고, 기준 상품이 없으면 빈 목록이다 (프론트가 섹션을 숨긴다).
     */
    public List<ProductSummaryDto> getRecommendations(List<Long> recentViewIds, int size) {
        int limit = Math.clamp(size, 1, MAX_SIZE);
        if (recentViewIds == null || recentViewIds.isEmpty()) return List.of();
        List<Long> ids = recentViewIds.stream().filter(Objects::nonNull).distinct().limit(policy.getRecentViewMax()).toList();

        return productRecommendSupport.pickInChunks(productRecommendSupport.rankRecent(ids), limit, new HashSet<>());
    }
}
