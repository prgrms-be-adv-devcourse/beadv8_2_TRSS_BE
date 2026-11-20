package com.backend.boundedContext.recommend.app;

import com.backend.boundedContext.recommend.domain.IngredientDictionary;
import com.backend.boundedContext.recommend.domain.IngredientDictionary.IngredientMatch;
import com.backend.boundedContext.recommend.domain.RecommendProduct;
import com.backend.boundedContext.recommend.out.RecommendProductRepository;
import com.backend.boundedContext.recommend.out.RecommendVectorRepository;
import com.backend.shared.product.event.ProductChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendApplyProductChangeUseCase {
    private final RecommendProductRepository recommendProductRepository;
    private final RecommendVectorRepository recommendVectorRepository;

    /**
     * 상품 변경을 추천용 사본에 반영한다. 전성분은 사전으로 읽어 태그를 만든다.
     * 임베딩할 글(카테고리·이름·설명)이 바뀌면 임베딩을 지워 스케줄러가 다시 만들게 한다. 가격·판매 여부만 바뀌면 그대로 둔다.
     */
    public void apply(ProductChangedEvent event, LocalDateTime eventAt) {
        IngredientMatch match = IngredientDictionary.match(event.ingredientsText());
        RecommendProduct product = recommendProductRepository.findById(event.productId()).orElse(null);

        if (product == null) {
            recommendProductRepository.save(RecommendProduct.create(event, eventAt, match));
        } else {
            String beforeHash = product.getEmbeddingSourceHash();
            if (!product.apply(event, eventAt, match)) {
                log.info("recommend product event skipped (older) productId={} eventAt={}", event.productId(), eventAt);
                return;
            }
            if (!beforeHash.equals(product.getEmbeddingSourceHash())) {
                recommendVectorRepository.clearProductEmbedding(event.productId());
            }
        }

        log.info("recommend product upserted productId={} onSale={} beauty={} tags={}",
                event.productId(), event.onSale(), event.beauty(), match.tags());
        logUnmatched(event.productId(), event.beauty(), match.unmatchedNames());
    }

    // 사전에 없는 성분명. 정제수·글리세린처럼 정상인 것이 대부분이고, 모아서 사전 보강에 쓴다
    private static void logUnmatched(Long productId, boolean beauty, List<String> unmatchedNames) {
        if (beauty && !unmatchedNames.isEmpty()) {
            log.info("recommend ingredients unmatched productId={} names={}", productId, unmatchedNames);
        }
    }
}
