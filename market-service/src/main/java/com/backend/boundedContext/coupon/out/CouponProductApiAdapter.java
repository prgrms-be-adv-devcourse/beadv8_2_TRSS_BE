package com.backend.boundedContext.coupon.out;

import com.backend.shared.product.dto.ProductSnapshotDto;
import com.backend.shared.product.out.ProductApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * {@link CouponProductPort} 구현. 같은 앱(market-service)의 상품 컨텍스트를 {@link ProductApi}로 부른다
 */
@Component
@RequiredArgsConstructor
public class CouponProductApiAdapter implements CouponProductPort {

    private final ProductApi productApi;

    @Override
    public void lockProductForShare(Long productId) {
        // 상품을 ID 순으로 FOR SHARE 잠금하는 기존 기능을 그대로 쓴다 (주문 변경 전 상품 잠금과 같은 잠금)
        productApi.lockProductsForOrderChange(List.of(productId));
    }

    @Override
    public List<ProductSnapshotDto> getProducts(Collection<Long> productIds) {
        return productApi.getProducts(productIds);
    }
}
