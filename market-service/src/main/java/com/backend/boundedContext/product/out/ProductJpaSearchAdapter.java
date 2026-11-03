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

import static com.backend.boundedContext.product.out.ProductSpecifications.categoryIn;
import static com.backend.boundedContext.product.out.ProductSpecifications.inStock;
import static com.backend.boundedContext.product.out.ProductSpecifications.nameContains;
import static com.backend.boundedContext.product.out.ProductSpecifications.statusEquals;

/**
 * DB로 상품을 검색한다. 상품명은 LIKE '%keyword%'(대소문자 무시)로 찾는다.
 */
@Repository
@RequiredArgsConstructor
public class ProductJpaSearchAdapter implements ProductSearchPort {

    private final ProductRepository productRepository;

    @Override
    public Page<Long> searchProductIds(ProductSearchQuery query, Pageable pageable) {
        Specification<Product> spec = Specification.allOf(
                statusEquals(ProductSaleStatus.ON_SALE),
                categoryIn(query.categoryIds()),
                nameContains(query.keyword()),
                query.excludeSoldOut() ? inStock() : Specification.unrestricted()
        );
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), toSort(query.sort()));

        return productRepository.findAll(spec, sorted).map(Product::getId);
    }

    private static Sort toSort(ProductSort sort) {
        Sort.Order byId = Sort.Order.desc("id");
        return switch (sort) {
            case LATEST -> Sort.by(Sort.Order.desc("createdAt"), byId);
            case PRICE_ASC -> Sort.by(Sort.Order.asc("price"), byId);
            case PRICE_DESC -> Sort.by(Sort.Order.desc("price"), byId);
        };
    }
}
