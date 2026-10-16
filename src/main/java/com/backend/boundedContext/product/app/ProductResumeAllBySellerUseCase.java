package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProductResumeAllBySellerUseCase {
    private final ProductSupport productSupport;

    /**
     * 판매자 철회 거절: 판매자 철회로 중지된 상품(stoppedBy = SELLER_WITHDRAWN)만 다시 판매 중으로 되돌린다.
     * 관리자 중지·판매 중지 요청 승인으로 멈춘 상품은 그대로 둔다. 되돌릴 상품이 없으면 아무것도 하지 않는다.
     * 철회 요청 때 취소된 결제 대기 주문은 되살리지 않는다.
     */
    public void resumeAllBySeller(Long sellerId) {
        productSupport.lockSellerProducts(sellerId, ProductSaleStatus.STOPPED).stream()
                .filter(Product::isStoppedBySellerWithdrawal)
                .forEach(Product::resumeFromSellerWithdrawal);
    }
}
