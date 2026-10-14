package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.ProductSummaryDto;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductOption;
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
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductSearchProductsUseCase {
    /** 검색 한 페이지의 최대 크기 (전역 최대 100보다 작게 둔다) */
    static final int MAX_PAGE_SIZE = 50;

    private final ProductSupport productSupport;
    private final ProductSearchPort productSearchPort;
    private final ProductRepository productRepository;

    /**
     * 판매 중이고 사용 중인 카테고리의 상품을 검색한다.
     * 검색 구현은 상품 ID만 찾고, 화면에 쓸 값(가격·품절 여부·상호명)은 여기서 DB와 회원 컨텍스트로 채운다.
     */
    public PageResponse<ProductSummaryDto> searchProducts(String keyword, Long categoryId, ProductSort sort,
                                                          boolean excludeSoldOut, Pageable pageable) {
        Pageable page = PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE));

        List<Long> categoryIds = productSupport.getDisplayableCategoryIds(categoryId);   // 없는 카테고리면 404
        if (categoryIds.isEmpty()) {
            return PageResponse.from(Page.empty(page));
        }

        ProductSearchQuery query = new ProductSearchQuery(normalize(keyword), categoryIds, excludeSoldOut, sort);
        Page<Long> productIds = productSearchPort.searchProductIds(query, page);

        Map<Long, ProductSummaryDto> summaries = toSummaries(productIds.getContent());
        return PageResponse.from(productIds, summaries::get);
    }

    private Map<Long, ProductSummaryDto> toSummaries(List<Long> productIds) {
        List<Product> products = productRepository.findAllById(productIds);
        Map<Long, List<ProductOption>> options = productSupport.getOptionsByProductId(productIds);
        Map<Long, String> businessNames = productSupport.getBusinessNames(
                products.stream().map(Product::getSellerId).collect(Collectors.toSet()));

        return products.stream()
                .map(product -> new ProductSummaryDto(
                        product.getId(), product.getName(), product.getPrice(), product.getCategory().getId(),
                        product.getSellerId(), businessNames.get(product.getSellerId()), null,
                        ProductSupport.isSoldOut(options.getOrDefault(product.getId(), List.of()))))
                .collect(Collectors.toMap(ProductSummaryDto::productId, Function.identity()));
    }

    /** 앞뒤 공백을 지우고, 비어 있으면 조건 없음(null) */
    private static String normalize(String keyword) {
        if (keyword == null || keyword.isBlank()) return null;
        return keyword.strip();
    }
}
