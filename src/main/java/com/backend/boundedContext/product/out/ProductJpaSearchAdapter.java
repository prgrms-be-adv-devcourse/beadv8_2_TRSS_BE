package com.backend.boundedContext.product.out;

import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import com.backend.boundedContext.product.domain.ProductSort;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Locale;

/**
 * DB로 상품을 검색한다. 상품명은 LIKE '%keyword%'(대소문자 무시)로 찾는다.
 */
@Repository
@RequiredArgsConstructor
public class ProductJpaSearchAdapter implements ProductSearchPort {
    private static final char LIKE_ESCAPE = '\\';

    private final ProductRepository productRepository;

    @Override
    public Page<Long> searchProductIds(ProductSearchQuery query, Pageable pageable) {
        Specification<Product> spec = Specification.allOf(
                onSale(),
                categoryIn(query.categoryIds()),
                nameContains(query.keyword()),
                query.excludeSoldOut() ? inStock() : Specification.unrestricted()
        );
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), toSort(query.sort()));

        return productRepository.findAll(spec, sorted).map(Product::getId);
    }

    private static Specification<Product> onSale() {
        return (root, q, cb) -> cb.equal(root.get("status"), ProductSaleStatus.ON_SALE);
    }

    private static Specification<Product> categoryIn(Collection<Long> categoryIds) {
        return (root, q, cb) -> root.get("category").get("id").in(categoryIds);
    }

    private static Specification<Product> nameContains(String keyword) {
        if (keyword == null) return Specification.unrestricted();
        String pattern = "%" + escapeLike(keyword.toLowerCase(Locale.ROOT)) + "%";
        return (root, q, cb) -> cb.like(cb.lower(root.get("name")), pattern, LIKE_ESCAPE);
    }

    /** 삭제되지 않은 옵션 중 재고가 남은 것이 하나라도 있는 상품 */
    private static Specification<Product> inStock() {
        return (root, q, cb) -> {
            Subquery<Long> sub = q.subquery(Long.class);
            Root<ProductOption> option = sub.from(ProductOption.class);
            sub.select(option.get("id")).where(
                    cb.equal(option.get("product"), root),
                    cb.isNull(option.get("deletedAt")),
                    cb.greaterThan(option.get("stock"), 0));
            return cb.exists(sub);
        };
    }

    private static Sort toSort(ProductSort sort) {
        Sort.Order byId = Sort.Order.desc("id");
        return switch (sort) {
            case LATEST -> Sort.by(Sort.Order.desc("createdAt"), byId);
            case PRICE_ASC -> Sort.by(Sort.Order.asc("price"), byId);
            case PRICE_DESC -> Sort.by(Sort.Order.desc("price"), byId);
        };
    }

    /** 사용자가 입력한 %, _ 를 와일드카드가 아닌 글자로 찾도록 이스케이프한다. */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
