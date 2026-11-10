package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductStopRequest;
import com.backend.boundedContext.product.domain.ProductStopRequestStatus;
import com.backend.boundedContext.product.out.ProductCouponPort;
import com.backend.boundedContext.product.out.ProductStopRequestRepository;
import com.backend.shared.order.dto.OrderCancelType;
import com.backend.shared.order.out.OrderApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * 상품이 판매를 멈춘 뒤(삭제·판매 중지) 함께 해야 하는 처리를 한곳에 둔다.
 * 판매자 삭제, 판매자 전체 중지, 관리자 즉시 중지·중지 요청 승인이 모두 이 메서드를 부른다.
 */
@Service
@RequiredArgsConstructor
public class ProductSaleEndSupport {
    // 상품 판매가 끝나 상품 쿠폰을 자동 중지할 때 남기는 사유
    static final String PRODUCT_COUPON_STOP_REASON = "대상 상품의 판매가 중지되거나 삭제되어 자동 중지";

    private final ProductStopRequestRepository productStopRequestRepository;
    private final OrderApi orderApi;
    private final ProductCouponPort productCouponPort;

    /**
     * 상태를 STOPPED·DELETED로 바꾼 상품들에 대해
     * 심사 중인 판매 중지 요청을 닫고(CANCELED), 결제 대기 주문 취소를 주문 컨텍스트에 요청하고 (재고 복원은 주문 쪽이 한다),
     * 그 상품의 상품 쿠폰을 쿠폰 컨텍스트에서 중지한다 (판매자 제재, 철회 요청으로 상품이 멈출 때도 여기를 지난다).
     * 상품이 없으면 아무것도 하지 않는다.
     */
    public void afterSaleEnded(Collection<Product> products) {
        if (products.isEmpty()) return;

        List<Long> productIds = products.stream().map(Product::getId).toList();
        productStopRequestRepository.findAllByProductIdInAndStatus(productIds, ProductStopRequestStatus.PENDING)
                .forEach(ProductStopRequest::cancel);
        orderApi.cancelPendingOrders(productIds, OrderCancelType.PRODUCT_STOPPED);
        productCouponPort.stopProductCoupons(productIds, PRODUCT_COUPON_STOP_REASON);
    }
}
