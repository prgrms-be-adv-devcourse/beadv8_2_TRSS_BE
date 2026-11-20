package com.backend.boundedContext.product.app;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ProductEndDiscountUseCase {
    private final ProductSupport productSupport;
    private final ProductDiscountSupport productDiscountSupport;

    /**
     * 판매자가 진행 중인 할인을 지금 끝낸다. 끝난 뒤에는 정가로 팔고, 그 사이 장바구니의 예상 금액이 달라져 주문이 409일 수 있다.
     * 다른 판매자·삭제된 상품과 없는 할인은 404, 판매 중지 상품이나 진행 중이 아닌 할인은 409.
     */
    public void endDiscount(Long sellerId, Long productId, Long discountId) {
        productSupport.lockSellerProduct(sellerId, productId);
        productDiscountSupport.getDiscount(productId, discountId).end(LocalDateTime.now());
    }
}
