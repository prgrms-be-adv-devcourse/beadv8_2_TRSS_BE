package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.SellerProductCountsDto;
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
    private final ProductImageSupport productImageSupport;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    /** 판매자 본인 상품 목록. 삭제된 상품은 빼고 최신순이며, 상태 필터가 null이면 전체다. */
    public PageResponse<SellerProductSummaryDto> getSellerProducts(Long sellerId, ProductDisplayStatus status,
                                                                   String keyword, Pageable pageable) {
        Pageable latest = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), LATEST);
        Page<Product> products = productRepository.findAll(base(sellerId, keyword).and(statusFilter(status)), latest);

        return toSummaries(products);
    }

    /** 탭별 개수. 목록과 같은 검색어를 적용한다. */
    public SellerProductCountsDto getSellerProductCounts(Long sellerId, String keyword) {
        Specification<Product> base = base(sellerId, keyword);

        return new SellerProductCountsDto(
                productRepository.count(base),
                productRepository.count(base.and(statusFilter(ProductDisplayStatus.ON_SALE))),
                productRepository.count(base.and(statusFilter(ProductDisplayStatus.SOLD_OUT))),
                productRepository.count(base.and(statusFilter(ProductDisplayStatus.STOPPED))));
    }

    /** 판매자 본인의 삭제되지 않은 상품 + 상품명 검색어 */
    private static Specification<Product> base(Long sellerId, String keyword) {
        return Specification.allOf(
                sellerEquals(sellerId),
                statusNotEquals(ProductSaleStatus.DELETED),
                nameContains(normalize(keyword)));
    }

    private PageResponse<SellerProductSummaryDto> toSummaries(Page<Product> products) {
        categoryRepository.findAll();   // 카테고리는 십여 개라 한 번에 읽어 두면 상품마다 따로 조회하지 않는다
        List<Long> productIds = products.map(Product::getId).getContent();
        Map<Long, List<ProductOption>> options = productSupport.getOptionsByProductId(productIds);
        Map<Long, String> thumbnailUrls = productImageSupport.getThumbnailUrls(productIds);

        return PageResponse.from(products, product -> {
            List<ProductOption> productOptions = options.getOrDefault(product.getId(), List.of());
            boolean soldOut = ProductSupport.isSoldOut(productOptions);
            return new SellerProductSummaryDto(
                    product.getId(),
                    productOptions.isEmpty() ? null : productOptions.getFirst().getId(),
                    thumbnailUrls.get(product.getId()),
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
