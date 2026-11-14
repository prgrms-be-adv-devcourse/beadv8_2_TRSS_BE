package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductDiscount;
import com.backend.boundedContext.product.out.ProductDiscountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ProductCreateDiscountUseCase {
    private final ProductSupport productSupport;
    private final ProductDiscountSupport productDiscountSupport;
    private final ProductDiscountRepository productDiscountRepository;

    /**
     * 판매자가 본인 상품에 기간 할인을 건다.
     * 상품을 쓰기 잠금한 뒤 다른 할인과 기간이 겹치는지 보므로, 동시에 등록해도 겹치지 않고 정가 수정과도 순서대로 처리된다.
     * 다른 판매자·삭제된 상품은 404, 판매 중지 상품은 409, 할인가가 정가의 10~99%가 아니거나 기간이 맞지 않으면 400,
     * 기간이 겹치면 409.
     */
    public ProductDiscount createDiscount(Long sellerId, Long productId, long salePrice,
                                          LocalDateTime startsAt, LocalDateTime endsAt) {
        Product product = productSupport.lockSellerProduct(sellerId, productId);
        ProductDiscount discount = ProductDiscount.create(product, salePrice, startsAt, endsAt, LocalDateTime.now());
        productDiscountSupport.checkNoOverlap(productId, startsAt, endsAt, null);
        return productDiscountRepository.save(discount);
    }
}
