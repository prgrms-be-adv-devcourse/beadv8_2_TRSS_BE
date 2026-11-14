package com.backend.boundedContext.product.app;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ProductDeleteDiscountUseCase {
    private final ProductSupport productSupport;
    private final ProductDiscountSupport productDiscountSupport;

    /**
     * 판매자가 시작 전인 할인을 지운다 (소프트 삭제). 상품을 쓰기 잠금한 뒤 지운다.
     * 다른 판매자·삭제된 상품과 없는 할인은 404, 판매 중지 상품이나 이미 시작한 할인은 409.
     */
    public void deleteDiscount(Long sellerId, Long productId, Long discountId) {
        productSupport.lockSellerProduct(sellerId, productId);
        productDiscountSupport.getDiscount(productId, discountId).delete(LocalDateTime.now());
    }
}
