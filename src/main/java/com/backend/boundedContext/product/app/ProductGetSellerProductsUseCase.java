package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.SellerProductListDto;
import com.backend.boundedContext.product.app.dto.SellerProductSummaryDto;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductDisplayStatus;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.global.page.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import static com.backend.boundedContext.product.out.ProductSpecifications.inStock;
import static com.backend.boundedContext.product.out.ProductSpecifications.nameContains;
import static com.backend.boundedContext.product.out.ProductSpecifications.sellerEquals;
import static com.backend.boundedContext.product.out.ProductSpecifications.soldOut;
import static com.backend.boundedContext.product.out.ProductSpecifications.statusEquals;
import static com.backend.boundedContext.product.out.ProductSpecifications.statusNotEquals;

@Service
@RequiredArgsConstructor
public class ProductGetSellerProductsUseCase {
    private static final Sort LATEST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final ProductSupport productSupport;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    /**
     * 판매자 본인 상품 목록(삭제된 상품 제외, 최신순)과 탭별 개수.
     * 상태 필터가 null이면 전체이고, 탭별 개수에는 상태 필터를 적용하지 않는다.
     */
    public SellerProductListDto getSellerProducts(Long sellerId, ProductDisplayStatus status, String keyword,
                                                  Pageable pageable) {
        Specification<Product> base = Specification.allOf(
                sellerEquals(sellerId),
                statusNotEquals(ProductSaleStatus.DELETED),
                nameContains(normalize(keyword)));

        Pageable latest = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), LATEST);
        Page<Product> products = productRepository.findAll(base.and(statusFilter(status)), latest);

        return new SellerProductListDto(toSummaries(products), new SellerProductListDto.Counts(
                productRepository.count(base),
                productRepository.count(base.and(statusFilter(ProductDisplayStatus.ON_SALE))),
                productRepository.count(base.and(statusFilter(ProductDisplayStatus.SOLD_OUT))),
                productRepository.count(base.and(statusFilter(ProductDisplayStatus.STOPPED)))));
    }

    private PageResponse<SellerProductSummaryDto> toSummaries(Page<Product> products) {
        categoryRepository.findAll();   // 카테고리는 십여 개라 한 번에 읽어 두면 상품마다 따로 조회하지 않는다
        Map<Long, List<ProductOption>> options = productSupport.getOptionsByProductId(
                products.map(Product::getId).getContent());

        return PageResponse.from(products, product -> {
            List<ProductOption> productOptions = options.getOrDefault(product.getId(), List.of());
            boolean soldOut = ProductSupport.isSoldOut(productOptions);
            return new SellerProductSummaryDto(
                    product.getId(),
                    productOptions.isEmpty() ? null : productOptions.getFirst().getId(),
                    null,
                    product.getName(),
                    product.getCategory().getName(),
                    product.getPrice(),
                    productOptions.stream().mapToInt(ProductOption::getStock).sum(),
                    ProductDisplayStatus.of(product, soldOut),
                    product.getStoppedBy(),
                    product.getStoppedReason(),
                    product.getCreatedAt());
        });
    }

    private static Specification<Product> statusFilter(ProductDisplayStatus status) {
        if (status == null) return Specification.unrestricted();
        return switch (status) {
            case ON_SALE -> statusEquals(ProductSaleStatus.ON_SALE).and(inStock());
            case SOLD_OUT -> statusEquals(ProductSaleStatus.ON_SALE).and(soldOut());
            case STOPPED -> statusEquals(ProductSaleStatus.STOPPED);
        };
    }

    private static String normalize(String keyword) {
        if (keyword == null || keyword.isBlank()) return null;
        return keyword.strip();
    }
}
