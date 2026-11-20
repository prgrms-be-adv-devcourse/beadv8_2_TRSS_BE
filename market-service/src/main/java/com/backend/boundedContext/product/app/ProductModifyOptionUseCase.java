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
     * 옵션 행만 잠그고 UPDATE하며 재고 행은 건드리지 않으므로 주문 재고 차감과 서로 기다리지 않는다.
     * 같은 옵션을 지우는 중이면 삭제 커밋을 기다린 뒤 삭제된 옵션으로 읽어 409가 된다 (삭제가 되살아나지 않게).
     * 다른 판매자의 상품·다른 상품의 옵션은 404, 판매 중지·삭제된 상품과 삭제된 옵션은 409.
     */
    public ProductOption modifyOption(Long sellerId, Long productId, Long optionId, long additionalPrice) {
        ProductOption option = productSupport.lockSellerOption(sellerId, productId, optionId);
        option.changeAdditionalPrice(additionalPrice);
        return option;
    }
}
