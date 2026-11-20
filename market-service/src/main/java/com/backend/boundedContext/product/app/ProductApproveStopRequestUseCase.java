package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.ProductStopRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductApproveStopRequestUseCase {
    private final ProductSupport productSupport;
    private final ProductSaleEndSupport productSaleEndSupport;

    /**
     * 관리자가 판매 중지 요청을 승인한다. 상품은 판매자가 적은 사유로 판매 중지(SELLER_REQUEST)되고,
     * 결제 대기 주문 취소를 요청한다. 없는 요청은 404, 이미 처리된 요청은 409.
     */
    public ProductStopRequest approve(Long requestId) {
        ProductStopRequest request = productSupport.lockStopRequest(requestId);

        request.approve();
        productSaleEndSupport.afterSaleEnded(List.of(request.getProduct()));
        return request;
    }
}
