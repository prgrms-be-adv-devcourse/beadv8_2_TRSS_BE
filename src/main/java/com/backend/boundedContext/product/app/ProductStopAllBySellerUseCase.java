package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import com.backend.shared.order.dto.OrderCancelType;
import com.backend.shared.order.out.OrderApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductStopAllBySellerUseCase {
    private final ProductSupport productSupport;
    private final OrderApi orderApi;

    /**
     * 판매자 철회 요청·제재: 판매자의 판매 중 상품을 모두 중지하고(stoppedBy = SELLER_WITHDRAWN),
     * 그 상품이 들어 있는 결제 대기 주문을 취소한다 (주문 컨텍스트가 재고도 복원한다).
     * 이미 중지·삭제된 상품은 건드리지 않고, 판매 중 상품이 없으면 아무것도 하지 않는다.
     */
    public void stopAllBySeller(Long sellerId, String reason) {
        List<Product> products = productSupport.lockSellerProducts(sellerId, ProductSaleStatus.ON_SALE);
        if (products.isEmpty()) return;

        products.forEach(product -> product.stopBySellerWithdrawal(reason));
        orderApi.cancelPendingOrders(products.stream().map(Product::getId).toList(), OrderCancelType.PRODUCT_STOPPED);
    }
}
