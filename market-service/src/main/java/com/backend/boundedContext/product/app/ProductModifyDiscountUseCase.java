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
     * 판매자가 할인을 부분 수정한다 (null인 값은 그대로). 시작 전이면 할인가·시작·끝을, 진행 중이면 끝 시각만 바꿀 수 있다.
     * 상품을 쓰기 잠금한 뒤 바뀐 기간이 다른 할인과 겹치는지 본다.
     * 다른 판매자·삭제된 상품과 없는 할인은 404, 판매 중지 상품은 409, 진행 중인 할인의 할인가·시작 시각을 바꾸려 하면 409,
     * 끝난 할인은 409, 값이 맞지 않으면 400, 다른 할인과 기간이 겹치면 409. 규칙은 {@link ProductDiscount#modify}를 따른다.
     */
    public void modifyDiscount(Long sellerId, Long productId, Long discountId, Long salePrice,
                               LocalDateTime startsAt, LocalDateTime endsAt) {
        productSupport.lockSellerProduct(sellerId, productId);
        ProductDiscount discount = productDiscountSupport.getDiscount(productId, discountId);

        discount.modify(salePrice, startsAt, endsAt, LocalDateTime.now());
        productDiscountSupport.checkNoOverlap(productId, discount.getStartsAt(), discount.getEndsAt(), discountId);
    }
}
