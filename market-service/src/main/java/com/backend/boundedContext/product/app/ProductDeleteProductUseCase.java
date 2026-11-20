package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.Product;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductDeleteProductUseCase {
    private final ProductSupport productSupport;
    private final ProductSaleEndSupport productSaleEndSupport;

    /**
     * 판매자가 본인 상품을 지운다 (소프트 삭제). 이미지 파일은 남겨 둔다.
     * 상품을 잠근 뒤 판단하므로 주문 생성과 동시에 들어와도 안전하다.
     * 다른 판매자·이미 삭제된 상품은 404, 판매 중지 상품은 409.
     */
    public void deleteProduct(Long sellerId, Long productId) {
        Product product = productSupport.lockSellerProduct(sellerId, productId);

        product.delete();
        productSaleEndSupport.afterSaleEnded(List.of(product));
    }
}
