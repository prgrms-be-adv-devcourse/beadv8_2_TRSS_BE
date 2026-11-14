package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.ProductSummaryDto;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductPrice;
import com.backend.boundedContext.product.domain.OptionStock;
import com.backend.boundedContext.product.domain.ProductSort;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.boundedContext.product.out.ProductSearchPort;
import com.backend.boundedContext.product.out.ProductSearchQuery;
import com.backend.global.page.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductSearchProductsUseCase {
    /** 검색 한 페이지의 최대 크기 (전역 최대 100보다 작게 둔다) */
    static final int MAX_PAGE_SIZE = 50;

    private final ProductSupport productSupport;
    private final ProductImageSupport productImageSupport;
    private final ProductDiscountSupport productDiscountSupport;
    private final ProductSearchPort productSearchPort;
    private final ProductRepository productRepository;

    /**
     * 판매 중이고 사용 중인 카테고리의 상품을 검색한다.
     * 검색 구현은 상품 ID만 찾고, 화면에 쓸 값(가격·품절 여부·상호명)은 여기서 DB와 회원 컨텍스트로 채운다.
     * 할인가는 조회 시각에 진행 중인 할인으로 채운다. 가격 정렬은 검색 구현이 정한다(ES는 할인가, DB 검색은 정가 기준).
     * sort가 null이면 검색어가 있을 때 관련도순, 없을 때 최신순이다.
     * 검색 색인이 늦거나 색인 반영에 실패해 지금은 보여 줄 수 없는 상품(판매 중지·삭제, 카테고리 사용 중지, DB에 없음)이 오면
     * DB의 현재 상태로 다시 걸러 결과에서 빼므로, 그 페이지는 size보다 적을 수 있다 (totalElements는 검색 구현의 값 그대로).
     */
    public PageResponse<ProductSummaryDto> searchProducts(String keyword, Long categoryId, ProductSort sort,
                                                          boolean excludeSoldOut, Pageable pageable) {
        Pageable page = PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE));

        List<Long> categoryIds = productSupport.getDisplayableCategoryIds(categoryId);   // 없는 카테고리면 404
        if (categoryIds.isEmpty()) {
            return PageResponse.from(Page.empty(page));
        }

        String normalizedKeyword = normalize(keyword);
        ProductSearchQuery query = new ProductSearchQuery(normalizedKeyword, categoryIds, excludeSoldOut,
                ProductSort.resolve(sort, normalizedKeyword != null));
        Page<Long> productIds = productSearchPort.searchProductIds(query, page);

        Map<Long, ProductSummaryDto> summaries = toSummaries(productIds.getContent());
        List<ProductSummaryDto> items = productIds.getContent().stream()
                .map(summaries::get)
                .filter(Objects::nonNull)
                .toList();
        return new PageResponse<>(items, productIds.getNumber(), productIds.getSize(),
                productIds.getTotalElements(), productIds.getTotalPages());
    }

    /** 검색 결과 ID 중 지금 구매자에게 보여 줄 수 있는 상품만 화면 값으로 만든다 */
    private Map<Long, ProductSummaryDto> toSummaries(List<Long> foundIds) {
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

        return products.stream()
                .map(product -> {
                    List<OptionStock> optionStocks = stocks.getOrDefault(product.getId(), List.of());
                    ProductPrice price = prices.get(product.getId());
                    return new ProductSummaryDto(
                            product.getId(), product.getName(), product.getPrice(), price.salePrice(), price.discountRate(),
                            product.getCategory().getId(),
                            product.getSellerId(), businessNames.get(product.getSellerId()),
                            thumbnailUrls.get(product.getId()),
                            ProductSupport.isSoldOut(optionStocks),
                            ProductSupport.getLowStockRemaining(optionStocks));
                })
                .collect(Collectors.toMap(ProductSummaryDto::productId, Function.identity()));
    }

    /** 앞뒤 공백을 지우고, 비어 있으면 조건 없음(null) */
    private static String normalize(String keyword) {
        if (keyword == null || keyword.isBlank()) return null;
        return keyword.strip();
    }
}
