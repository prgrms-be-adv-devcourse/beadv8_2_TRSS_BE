package com.backend.boundedContext.product.out;

import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import com.backend.boundedContext.product.domain.ProductSort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

import static com.backend.boundedContext.product.out.ProductSpecifications.categoryIn;
import static com.backend.boundedContext.product.out.ProductSpecifications.discountedAt;
import static com.backend.boundedContext.product.out.ProductSpecifications.inStock;
import static com.backend.boundedContext.product.out.ProductSpecifications.nameContains;
import static com.backend.boundedContext.product.out.ProductSpecifications.orderByDiscountRate;
import static com.backend.boundedContext.product.out.ProductSpecifications.statusEquals;

/**
 * DB로 상품을 검색한다. 상품명은 LIKE '%keyword%'(대소문자 무시)로 찾는다.
 * 테스트(engine=jpa)에서 쓰고, ES 검색이 실패하면 대신 쓴다. 관련도순은 최신순, 가격순은 정가 기준이다.
 */
@Repository
@RequiredArgsConstructor
public class ProductJpaSearchAdapter implements ProductSearchPort {

    private final ProductRepository productRepository;

    @Override
    public Page<Long> searchProductIds(ProductSearchQuery query, Pageable pageable) {
        LocalDateTime now = LocalDateTime.now();
        Specification<Product> spec = Specification.allOf(
                statusEquals(ProductSaleStatus.ON_SALE),
                categoryIn(query.categoryIds()),
                nameContains(query.keyword()),
                query.excludeSoldOut() ? inStock() : Specification.unrestricted(),
                query.discountedOnly() ? discountedAt(now) : Specification.unrestricted()
        );
        if (query.sort() == ProductSort.DISCOUNT_RATE) {
            // 할인율은 정가와 진행 중 할인의 할인가로 계산하는 값이라 Pageable 정렬로 쓸 수 없어 Specification에서 정렬한다
            Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
            return productRepository.findAll(spec.and(orderByDiscountRate(now)), unsorted).map(Product::getId);
        }
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), toSort(query.sort()));

        return productRepository.findAll(spec, sorted).map(Product::getId);
    }

    private static Sort toSort(ProductSort sort) {
        Sort.Order byId = Sort.Order.desc("id");
        return switch (sort) {
            // 관련도는 DB 검색에 없고, 행동 지표(찜·별점·판매량)는 검색 색인에만 있어 최신순으로 대신한다
            case RELEVANCE, LATEST, POPULAR, WISH, SALES -> Sort.by(Sort.Order.desc("createdAt"), byId);
            case DISCOUNT_RATE -> throw new IllegalArgumentException("할인율순은 orderByDiscountRate로 정렬한다");
            case PRICE_ASC -> Sort.by(Sort.Order.asc("price"), byId);
            case PRICE_DESC -> Sort.by(Sort.Order.desc("price"), byId);
        };
    }
}
