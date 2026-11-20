package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.Category;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.out.ProductSimilarQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비슷한 상품의 기준 상품 읽기. 짧은 읽기 트랜잭션에서 필요한 값만 꺼내, 후보 조회(ES)는 트랜잭션 밖에서 하게 한다.
 */
@Component
@RequiredArgsConstructor
class ProductSimilarSupport {
    private final ProductSupport productSupport;
    private final ProductDiscountSupport productDiscountSupport;

    /** 기준 상품이 없거나 보여 줄 수 없으면 404 */
    @Transactional(readOnly = true)
    public SimilarBase readBase(Long productId, int candidateSize) {
        Product product = productSupport.getDisplayableProduct(productId);
        Category category = product.getCategory();
        ProductSimilarQuery query = new ProductSimilarQuery(productId, likeText(product), category.getId(),
                productSupport.getDisplayableCategoryIds(category.getParent().getId()), candidateSize);
        return new SimilarBase(query, productDiscountSupport.getPrice(product).basePrice());
    }

    /** 비슷한 상품을 찾을 기준 텍스트 (상품명 + 설명) */
    static String likeText(Product product) {
        String description = product.getDescription();
        return description == null ? product.getName() : product.getName() + "\n" + description;
    }

    /**
     * @param query     후보 조회 조건
     * @param basePrice 기준 상품의 지금 기본가 (할인 중이면 할인가)
     */
    record SimilarBase(ProductSimilarQuery query, long basePrice) {
    }
}
