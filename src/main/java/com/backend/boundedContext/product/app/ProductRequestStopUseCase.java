package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductErrorCode;
import com.backend.boundedContext.product.domain.ProductStopRequest;
import com.backend.boundedContext.product.domain.ProductStopRequestStatus;
import com.backend.boundedContext.product.out.ProductStopRequestRepository;
import com.backend.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProductRequestStopUseCase {
    private final ProductSupport productSupport;
    private final ProductStopRequestRepository productStopRequestRepository;

    /**
     * 판매자가 본인 상품의 판매 중지를 요청한다. 관리자가 승인해야 중지되고, 그동안 판매는 계속된다.
     * 상품을 잠근 뒤 판단하므로 같은 요청이 동시에 오거나 삭제와 겹쳐도 하나만 처리된다.
     * 다른 판매자·삭제된 상품은 404, 판매 중지 상품은 409, 심사 중인 요청이 이미 있으면 409.
     */
    public ProductStopRequest requestStop(Long sellerId, Long productId, String reason) {
        Product product = productSupport.lockSellerProduct(sellerId, productId);
        if (productStopRequestRepository.existsByProductIdAndStatus(productId, ProductStopRequestStatus.PENDING)) {
            throw new DomainException(ProductErrorCode.STOP_REQUEST_PENDING);
        }

        return productStopRequestRepository.save(ProductStopRequest.create(product, reason));
    }
}
