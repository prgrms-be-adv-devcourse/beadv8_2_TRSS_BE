package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.ProductSummaryDto;
import com.backend.boundedContext.product.domain.OptionStock;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductPrice;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.shared.review.dto.ProductRatingStatsDto;
import com.backend.shared.review.out.ReviewApi;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 목록 카드(ProductSummaryDto) 만들기. 검색 구현이 찾은 상품 ID를 DB의 지금 값으로 다시 거르고 화면 값을 채운다.
 * 상품 검색·비슷한 상품·추천이 같이 쓴다.
 *
 * <p>DB 값은 짧은 읽기 트랜잭션에서 읽고, 상호명(회원 서비스 HTTP)은 그 트랜잭션이 끝난 뒤에 채운다.
 * 회원 서비스가 느려도 DB 커넥션을 잡고 기다리지 않게 하고, 실패하면 상호명 없이 보여 준다.
 */
@Component
public class ProductSummarySupport {
    private final ProductSupport productSupport;
    private final ProductImageSupport productImageSupport;
    private final ProductDiscountSupport productDiscountSupport;
    private final ProductRepository productRepository;
    private final ReviewApi reviewApi;
    private final TransactionTemplate readOnlyTransaction;

    public ProductSummarySupport(ProductSupport productSupport, ProductImageSupport productImageSupport,
                                 ProductDiscountSupport productDiscountSupport, ProductRepository productRepository,
                                 ReviewApi reviewApi, PlatformTransactionManager transactionManager) {
        this.productSupport = productSupport;
        this.productImageSupport = productImageSupport;
        this.productDiscountSupport = productDiscountSupport;
        this.productRepository = productRepository;
        this.reviewApi = reviewApi;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    /** 검색 결과 ID 중 지금 구매자에게 보여 줄 수 있는 상품만 화면 값으로 만든다 */
    public Map<Long, ProductSummaryDto> toSummaries(List<Long> foundIds) {
        Map<Long, ProductSummaryDto> summaries = readOnlyTransaction.execute(status -> readSummaries(foundIds));
        if (summaries.isEmpty()) return summaries;

        Set<Long> sellerIds = summaries.values().stream().map(ProductSummaryDto::sellerId).collect(Collectors.toSet());
        Map<Long, String> businessNames = productSupport.getBusinessNamesOrEmpty(sellerIds);
        return summaries.values().stream()
                .map(summary -> summary.withBusinessName(businessNames.get(summary.sellerId())))
                .collect(Collectors.toMap(ProductSummaryDto::productId, Function.identity()));
    }

    /** DB 값만으로 만든 화면 값 (상호명은 비어 있다) */
    private Map<Long, ProductSummaryDto> readSummaries(List<Long> foundIds) {
        // 검색 구현(ES)의 판매 상태는 색인 시점 값이므로 DB의 현재 상태로 다시 거른다
        List<Product> products = productRepository.findAllById(foundIds).stream()
                .filter(Product::isDisplayable)
                .toList();
        if (products.isEmpty()) return Map.of();
        List<Long> productIds = products.stream().map(Product::getId).toList();

        Map<Long, List<OptionStock>> stocks = productSupport.getOptionStocksByProductId(productIds);
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
                            product.getSellerId(), null,
                            thumbnailUrls.get(product.getId()),
                            ProductSupport.isSoldOut(optionStocks),
                            ProductSupport.getLowStockRemaining(optionStocks),
                            rating.reviewCount(), rating.averageRating());
                })
                .collect(Collectors.toMap(ProductSummaryDto::productId, Function.identity()));
    }
}
