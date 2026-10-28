package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductStopAllBySellerUseCase {
    private final ProductSupport productSupport;
    private final ProductSaleEndSupport productSaleEndSupport;

    /**
     * 판매자 철회 요청·제재: 판매자의 판매 중 상품을 모두 중지하고(stoppedBy = SELLER_WITHDRAWN),
     * 심사 중인 판매 중지 요청을 닫고 결제 대기 주문 취소를 요청한다 (주문 컨텍스트가 재고도 복원한다).
     * 이미 중지·삭제된 상품은 건드리지 않고, 판매 중 상품이 없으면 아무것도 하지 않는다.
     */
    public void stopAllBySeller(Long sellerId, String reason) {
        List<Product> products = productSupport.lockSellerProducts(sellerId, ProductSaleStatus.ON_SALE);

        products.forEach(product -> product.stopBySellerWithdrawal(reason));
        productSaleEndSupport.afterSaleEnded(products);
    }
}
