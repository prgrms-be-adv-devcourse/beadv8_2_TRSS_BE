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
    private final ProductDiscountSupport productDiscountSupport;

    /**
     * 판매자 본인 상품의 정보와 이미지를 수정한다. null인 항목은 그대로 둔다. 재고는 재고 조정으로만 바꾼다.
     * 다른 판매자·삭제된 상품은 404, 판매 중지 상품은 409(이미지만 바꿔도), 고를 수 없는 카테고리는 400.
     * 가격을 바꾸려는데 진행 중이거나 예약된 할인이 있으면 409(할인가는 정가 기준으로 검사했으므로).
     * 가격을 바꿀 때는 상품을 쓰기 잠금한 뒤 할인을 보므로, 동시에 할인을 등록해도 둘 중 하나만 반영된다.
     * 이미지 규칙은 {@link ProductImageSupport#modifyImages}를 따른다.
     */
    public Product modifyProduct(Long sellerId, Long productId, String name, Long price, Long categoryId,
                                 String description, List<Long> keepDetailImageIds, ProductImageFile thumbnail,
                                 List<ProductImageFile> newDetailImages) {
        Product product = productSupport.getSellerProduct(sellerId, productId);
        // 가격이 실제로 바뀔 때만 쓰기 잠금한다 (폼이 같은 가격을 보내도 주문 재고 차감과 기다리지 않게)
        if (price != null && price != product.getPrice()) {
            product = productSupport.lockSellerProduct(sellerId, productId);   // 잠근 뒤 DB 값으로 다시 읽는다
        }
        Category category = categoryId == null ? null : productSupport.getCategory(categoryId);

        if (price != null && price != product.getPrice()) {
            product.checkEditable();   // 판매 중지 상품은 할인보다 먼저 409(PRODUCT_NOT_EDITABLE)
            productDiscountSupport.checkNoPendingDiscount(productId);
        }

        product.modify(name, price, category, description);   // 판매 중지·삭제 상품이면 여기서 막힌다
        productImageSupport.modifyImages(product, thumbnail, keepDetailImageIds, newDetailImages);
        return product;
    }
}
