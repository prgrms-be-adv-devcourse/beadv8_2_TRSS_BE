package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.ProductSummaryDto;
import com.backend.boundedContext.product.domain.OptionStock;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductPrice;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.shared.review.dto.ProductRatingStatsDto;
import com.backend.shared.review.out.ReviewApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 목록 카드(ProductSummaryDto) 만들기. 검색 구현이 찾은 상품 ID를 DB의 지금 값으로 다시 거르고 화면 값을 채운다.
 * 상품 검색과 비슷한 상품이 같이 쓴다.
 */
@Component
@RequiredArgsConstructor
public class ProductSummarySupport {
    private final ProductSupport productSupport;
    private final ProductImageSupport productImageSupport;
    private final ProductDiscountSupport productDiscountSupport;
    private final ProductRepository productRepository;
    private final ReviewApi reviewApi;

    /** 검색 결과 ID 중 지금 구매자에게 보여 줄 수 있는 상품만 화면 값으로 만든다 */
    @Transactional(readOnly = true)   // 호출한 쪽 트랜잭션이 있으면 그 안에서, 없으면(비슷한 상품) 이 조회만 짧게 잡는다
    public Map<Long, ProductSummaryDto> toSummaries(List<Long> foundIds) {
        // 검색 구현(ES)의 판매 상태는 색인 시점 값이므로 DB의 현재 상태로 다시 거른다
        List<Product> products = productRepository.findAllById(foundIds).stream()
                .filter(Product::isDisplayable)
                .toList();
        if (products.isEmpty()) return Map.of();
        List<Long> productIds = products.stream().map(Product::getId).toList();

        Map<Long, List<OptionStock>> stocks = productSupport.getOptionStocksByProductId(productIds);
        Map<Long, String> businessNames = productSupport.getBusinessNames(
                products.stream().map(Product::getSellerId).collect(Collectors.toSet()));
        Map<Long, String> thumbnailUrls = productImageSupport.getThumbnailUrls(productIds);
        Map<Long, ProductPrice> prices = productDiscountSupport.getPrices(products);
        // 리뷰 수·평균 별점은 색인 값이 아니라 지금 DB 값이다 (리뷰 직후에도 맞고, DB 검색으로 대신할 때도 같다)
        Map<Long, ProductRatingStatsDto> ratings = reviewApi.getRatingStats(productIds);

        return products.stream()
                .map(product -> {
                    List<OptionStock> optionStocks = stocks.getOrDefault(product.getId(), List.of());
                    ProductPrice price = prices.get(product.getId());
                    ProductRatingStatsDto rating = ratings.getOrDefault(product.getId(), ProductRatingStatsDto.empty(product.getId()));
                    return new ProductSummaryDto(
                            product.getId(), product.getName(), product.getPrice(), price.salePrice(), price.discountRate(),
                            product.getSellerId(), businessNames.get(product.getSellerId()),
                            thumbnailUrls.get(product.getId()),
                            ProductSupport.isSoldOut(optionStocks),
                            ProductSupport.getLowStockRemaining(optionStocks),
                            rating.reviewCount(), rating.averageRating());
                })
                .collect(Collectors.toMap(ProductSummaryDto::productId, Function.identity()));
    }
}
