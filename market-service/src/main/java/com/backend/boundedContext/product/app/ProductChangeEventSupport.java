package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.OptionStock;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.shared.product.event.ProductChangedEvent;
import com.backend.shared.product.event.ProductMetricsChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 상품 변경 이벤트(ProductChangedEvent)를 만든다. 상품을 바꾼 트랜잭션 안에서 불러 outbox에 같이 저장한다.
 * 상품의 지금 값(DB)을 읽어 담으므로, 바꾼 뒤에 부른다.
 */
@Service
@RequiredArgsConstructor
public class ProductChangeEventSupport {
    /** 한 번에 읽어 이벤트로 만드는 상품 수 (상품·대표 이미지·재고를 IN으로 읽는다) */
    private static final int BATCH_SIZE = 500;

    private final ProductRepository productRepository;
    private final ProductImageSupport productImageSupport;
    private final ProductSupport productSupport;
    private final EventPublisher eventPublisher;

    public void publishChanged(Collection<Long> productIds) {
        List<Long> ids = List.copyOf(productIds);
        for (int from = 0; from < ids.size(); from += BATCH_SIZE) {
            publishBatch(ids.subList(from, Math.min(from + BATCH_SIZE, ids.size())));
        }
    }

    private void publishBatch(List<Long> productIds) {

        List<Product> products = productRepository.findAllById(productIds).stream()
                .sorted(Comparator.comparing(Product::getId))
                .toList();
        Map<Long, String> thumbnails = productImageSupport.getThumbnailUrls(productIds);
        Map<Long, List<OptionStock>> stocks = productSupport.getOptionStocksByProductId(productIds);
        products.forEach(product -> eventPublisher.publish(toEvent(product, thumbnails.get(product.getId()),
                ProductSupport.isSoldOut(stocks.getOrDefault(product.getId(), List.of())))));
    }

    /** 상품들의 행동 지표 변경 이벤트를 발행한다 (검색 색인 컨슈머만 받는다). 상품을 읽지 않고 ID만 담는다 */
    public void publishMetricsChanged(Collection<Long> productIds) {
        productIds.stream().distinct().sorted().forEach(id -> eventPublisher.publish(new ProductMetricsChangedEvent(id)));
    }

    /**
     * 판매 중인 상품 전체의 변경 이벤트를 다시 발행하고 발행 수를 돌려준다.
     * AI 서비스를 처음 띄우거나 AI 쪽 상품 사본을 비웠을 때 관리자가 부른다.
     * shortcut: 한 트랜잭션에서 전부 발행한다. 상품이 수만 개가 되면 배치마다 트랜잭션을 나눈다.
     */
    public int publishAllOnSale() {
        long afterId = 0L;
        int published = 0;
        while (true) {
            List<Long> ids = productRepository.findIdsByStatusAfter(ProductSaleStatus.ON_SALE, afterId, Limit.of(BATCH_SIZE));
            if (ids.isEmpty()) break;
            publishChanged(ids);
            published += ids.size();
            afterId = ids.getLast();
            if (ids.size() < BATCH_SIZE) break;
        }
        return published;
    }

    private static ProductChangedEvent toEvent(Product product, String thumbnailUrl, boolean soldOut) {
        return new ProductChangedEvent(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getCategory().getName(),
                product.getCategory().isBeauty(),
                product.getIngredientsText(),
                product.getPrice(),
                thumbnailUrl,
                product.isDisplayable(),
                soldOut);
    }
}
