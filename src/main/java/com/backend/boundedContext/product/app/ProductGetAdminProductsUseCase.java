package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.AdminProductSummaryDto;
import com.backend.boundedContext.product.domain.AdminProductDisplayStatus;
import com.backend.boundedContext.product.domain.Product;
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

import static com.backend.boundedContext.product.out.ProductSpecifications.categoryIn;
import static com.backend.boundedContext.product.out.ProductSpecifications.inStock;
import static com.backend.boundedContext.product.out.ProductSpecifications.nameContains;
import static com.backend.boundedContext.product.out.ProductSpecifications.sellerEquals;
import static com.backend.boundedContext.product.out.ProductSpecifications.soldOut;
import static com.backend.boundedContext.product.out.ProductSpecifications.statusEquals;

@Service
@RequiredArgsConstructor
public class ProductGetAdminProductsUseCase {
    private static final Sort LATEST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final ProductSupport productSupport;
    private final ProductImageSupport productImageSupport;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    /**
     * 관리자 상품 목록. 삭제된 상품까지 최신 등록순으로 주고, null인 조건은 적용하지 않는다.
     * 카테고리가 대분류면 그 아래 소분류 상품까지 보여 주고, 사용 중지된 카테고리도 포함한다. 없는 카테고리면 404.
     */
    public PageResponse<AdminProductSummaryDto> getProducts(Long sellerId, AdminProductDisplayStatus status, Long categoryId,
                                                            String keyword, Pageable pageable) {
        List<Long> categoryIds = productSupport.getCategoryIdsForAdmin(categoryId);
        Specification<Product> spec = Specification.allOf(
                sellerId == null ? Specification.unrestricted() : sellerEquals(sellerId),
                statusFilter(status),
                categoryIds == null ? Specification.unrestricted() : categoryIn(categoryIds),
                nameContains(normalize(keyword)));

        Pageable latest = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), LATEST);
        return toSummaries(productRepository.findAll(spec, latest));
    }

    private PageResponse<AdminProductSummaryDto> toSummaries(Page<Product> products) {
        categoryRepository.findAll();   // 카테고리는 십여 개라 한 번에 읽어 두면 상품마다 따로 조회하지 않는다
        List<Long> productIds = products.map(Product::getId).getContent();
        Map<Long, List<ProductOption>> options = productSupport.getOptionsByProductId(productIds);
        Map<Long, String> thumbnailUrls = productImageSupport.getThumbnailUrls(productIds);

        return PageResponse.from(products, product -> {
            List<ProductOption> productOptions = options.getOrDefault(product.getId(), List.of());
            return new AdminProductSummaryDto(
                    product.getId(),
                    product.getSellerId(),
                    thumbnailUrls.get(product.getId()),
                    product.getName(),
                    product.getCategory().getName(),
                    product.getPrice(),
                    productOptions.stream().mapToInt(ProductOption::getStock).sum(),
                    AdminProductDisplayStatus.of(product, ProductSupport.isSoldOut(productOptions)),
                    product.getStoppedBy(),
                    product.getStoppedReason(),
                    product.getStoppedAt(),
                    product.getCreatedAt());
        });
    }

    /** 화면 상태 필터. 품절은 판매 중 상품 중 재고가 모두 0인 것이다 (판매자 목록과 같은 기준) */
    private static Specification<Product> statusFilter(AdminProductDisplayStatus status) {
        if (status == null) return Specification.unrestricted();
        return switch (status) {
            case ON_SALE -> statusEquals(ProductSaleStatus.ON_SALE).and(inStock());
            case SOLD_OUT -> statusEquals(ProductSaleStatus.ON_SALE).and(soldOut());
            case STOPPED -> statusEquals(ProductSaleStatus.STOPPED);
            case DELETED -> statusEquals(ProductSaleStatus.DELETED);
        };
    }

    private static String normalize(String keyword) {
        if (keyword == null || keyword.isBlank()) return null;
        return keyword.strip();
    }
}
