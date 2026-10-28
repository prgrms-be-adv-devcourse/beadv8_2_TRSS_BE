package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.Product;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductStopByAdminUseCase {
    private final ProductSupport productSupport;
    private final ProductSaleEndSupport productSaleEndSupport;

    /**
     * 관리자가 사유를 적고 상품 판매를 바로 멈춘다. 해제는 없다.
     * 상품을 잠근 뒤 판단하므로 주문 생성·판매자 삭제와 겹쳐도 안전하다.
     * 없는 상품은 404, 이미 판매 중지·삭제된 상품은 409.
     */
    public void stopProduct(Long productId, String reason) {
        Product product = productSupport.lockProduct(productId);

        product.stopByAdmin(reason);
        productSaleEndSupport.afterSaleEnded(List.of(product));
    }
}
