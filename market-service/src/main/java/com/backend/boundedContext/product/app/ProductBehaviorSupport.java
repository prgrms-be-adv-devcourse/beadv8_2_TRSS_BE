package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.ProductBehaviorPolicy;
import com.backend.boundedContext.product.domain.ProductBehaviorScore;
import com.backend.boundedContext.product.out.wishlist.ProductWishCount;
import com.backend.boundedContext.product.out.wishlist.WishlistRepository;
import com.backend.shared.order.out.OrderApi;
import com.backend.shared.review.dto.ProductRatingStatsDto;
import com.backend.shared.review.out.ReviewApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 상품 행동 지표(찜·별점·판매량)와 행동 점수. 찜은 상품 컨텍스트에서, 리뷰와 판매량은 ReviewApi·OrderApi로 상품을 모아 한 번씩 읽는다.
 * 판매량을 바꾸는 주문(결제·취소·반품)과 집계 구간 밖으로 나가는 판매도 여기서 찾는다(판매 지표 스케줄러).
 */
@Service
@RequiredArgsConstructor
public class ProductBehaviorSupport {
    private final WishlistRepository wishlistRepository;
    private final ReviewApi reviewApi;
    private final OrderApi orderApi;
    private final ProductBehaviorPolicy policy;

    /** 상품별 행동 지표와 점수 (모든 상품에 값이 있다. 활동이 없으면 0) */
    public Map<Long, ProductBehaviorScore> getScores(Collection<Long> productIds, LocalDateTime now) {
        if (productIds.isEmpty()) return Map.of();

        Map<Long, Long> wishCounts = wishlistRepository.countByProductIdIn(productIds).stream()
                .collect(Collectors.toMap(ProductWishCount::productId, ProductWishCount::wishCount));
        Map<Long, ProductRatingStatsDto> ratings = reviewApi.getRatingStats(productIds);
        Map<Long, Long> sales = orderApi.getSalesQuantities(productIds, salesWindowStart(now));

        return productIds.stream().distinct().collect(Collectors.toMap(Function.identity(), id -> {
            ProductRatingStatsDto rating = ratings.get(id);
            return ProductBehaviorScore.of(policy,
                    wishCounts.getOrDefault(id, 0L),
                    rating == null ? 0 : rating.reviewCount(),
                    rating == null ? 0 : rating.ratingSum(),
                    sales.getOrDefault(id, 0L));
        }));
    }

    /** 상품 상세의 찜 수 (색인 값이 아니라 지금 값) */
    public long getWishCount(Long productId) {
        return wishlistRepository.countByProductId(productId);
    }

    /**
     * (from, to] 사이에 판매량이 바뀌었을 수 있는 상품 ID.
     * 그사이 결제·취소·반품된 주문의 상품과, 그사이 판매량 집계 구간(최근 salesWindowDays일) 밖으로 나간 결제의 상품이다.
     */
    public List<Long> findProductIdsWithSalesChanged(LocalDateTime from, LocalDateTime to) {
        List<Long> changed = orderApi.findProductIdsWithSalesChanged(from, to);
        List<Long> expired = orderApi.findProductIdsPaidBetween(salesWindowStart(from), salesWindowStart(to));
        return Stream.concat(changed.stream(), expired.stream()).distinct().toList();
    }

    private LocalDateTime salesWindowStart(LocalDateTime now) {
        return now.minusDays(policy.getSalesWindowDays());
    }
}
