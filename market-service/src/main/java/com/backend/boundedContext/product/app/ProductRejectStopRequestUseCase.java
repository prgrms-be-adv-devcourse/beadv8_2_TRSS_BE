package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.ProductStopRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProductRejectStopRequestUseCase {
    private final ProductSupport productSupport;

    /**
     * 관리자가 반려 사유를 적고 판매 중지 요청을 반려한다. 상품은 그대로 판매 중이다.
     * 없는 요청은 404, 이미 처리된 요청은 409.
     */
    public ProductStopRequest reject(Long requestId, String rejectReason) {
        ProductStopRequest request = productSupport.lockStopRequest(requestId);

        request.reject(rejectReason);
        return request;
    }
}
