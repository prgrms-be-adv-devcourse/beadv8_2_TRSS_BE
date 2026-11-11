package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.ProductDiscount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ProductModifyDiscountUseCase {
    private final ProductSupport productSupport;
    private final ProductDiscountSupport productDiscountSupport;

    /**
     * 판매자가 시작 전인 할인의 할인가와 기간을 바꾼다. 상품을 쓰기 잠금한 뒤 다른 할인과 겹치는지 본다.
     * 다른 판매자·삭제된 상품과 없는 할인은 404, 판매 중지 상품이나 이미 시작한 할인은 409,
     * 값이 맞지 않으면 400, 다른 할인과 기간이 겹치면 409.
     */
    public void modifyDiscount(Long sellerId, Long productId, Long discountId, long salePrice,
                               LocalDateTime startsAt, LocalDateTime endsAt) {
        productSupport.lockSellerProduct(sellerId, productId);
        ProductDiscount discount = productDiscountSupport.getDiscount(productId, discountId);

        discount.modify(salePrice, startsAt, endsAt, LocalDateTime.now());
        productDiscountSupport.checkNoOverlap(productId, startsAt, endsAt, discountId);
    }
}
