package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.ProductImageFile;
import com.backend.boundedContext.product.domain.Category;
import com.backend.boundedContext.product.domain.Product;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductModifyProductUseCase {
    private final ProductSupport productSupport;
    private final ProductImageSupport productImageSupport;

    /**
     * 판매자 본인 상품의 정보와 이미지를 수정한다. null인 항목은 그대로 둔다. 재고는 재고 조정으로만 바꾼다.
     * 다른 판매자·삭제된 상품은 404, 판매 중지 상품은 409(이미지만 바꿔도), 고를 수 없는 카테고리는 400.
     * 이미지 규칙은 {@link ProductImageSupport#modifyImages}를 따른다.
     */
    public Product modifyProduct(Long sellerId, Long productId, String name, Long price, Long categoryId,
                                 String description, List<Long> keepDetailImageIds, ProductImageFile thumbnail,
                                 List<ProductImageFile> newDetailImages) {
        Product product = productSupport.getSellerProduct(sellerId, productId);
        Category category = categoryId == null ? null : productSupport.getCategory(categoryId);

        product.modify(name, price, category, description);   // 판매 중지·삭제 상품이면 여기서 막힌다
        productImageSupport.modifyImages(product, thumbnail, keepDetailImageIds, newDetailImages);
        return product;
    }
}
