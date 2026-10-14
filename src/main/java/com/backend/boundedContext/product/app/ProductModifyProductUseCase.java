package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.Category;
import com.backend.boundedContext.product.domain.Product;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProductModifyProductUseCase {
    private final ProductSupport productSupport;

    /**
     * 판매자 본인 상품의 정보를 수정한다. null인 항목은 그대로 둔다. 재고는 재고 조정으로만 바꾼다.
     * 다른 판매자·삭제된 상품은 404, 판매 중지 상품은 409, 고를 수 없는 카테고리는 400.
     */
    public Product modifyProduct(Long sellerId, Long productId, String name, Long price, Long categoryId,
                                 String description) {
        Product product = productSupport.getSellerProduct(sellerId, productId);
        Category category = categoryId == null ? null : productSupport.getCategory(categoryId);

        product.modify(name, price, category, description);
        return product;
    }
}
