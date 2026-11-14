package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.ProductOption;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProductModifyOptionUseCase {
    private final ProductSupport productSupport;

    /**
     * 판매자가 옵션의 추가금을 바꾼다. 옵션 값은 바꿀 수 없다 (지난 주문·리뷰가 옵션 이름을 그대로 들고 있으므로, 다른 조합이면 새로 만든다).
     * 옵션 행만 UPDATE하고 재고 행은 건드리지 않으므로 주문 재고 차감과 서로 기다리지 않는다.
     * 다른 판매자의 상품·다른 상품의 옵션은 404, 판매 중지·삭제된 상품과 삭제된 옵션은 409.
     */
    public ProductOption modifyOption(Long sellerId, Long productId, Long optionId, long additionalPrice) {
        ProductOption option = productSupport.getSellerOption(sellerId, productId, optionId);
        option.changeAdditionalPrice(additionalPrice);
        return option;
    }
}
