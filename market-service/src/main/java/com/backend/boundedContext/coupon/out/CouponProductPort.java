package com.backend.boundedContext.coupon.out;

import com.backend.shared.product.dto.ProductSnapshotDto;

import java.util.Collection;
import java.util.List;

/**
 * 쿠폰이 쓰는 상품 기능
 */
public interface CouponProductPort {

    /**
     * 상품을 읽기 잠금(FOR SHARE)한다. 쿠폰을 발행하는 동안 상품 판매 중지, 삭제가 끼어들지 못하게 한다.
     * 판매 중지, 삭제는 상품을 쓰기 잠금한 뒤 그 상품의 쿠폰을 중지하므로, 잠금 없이 발행하면
     * 판매 중지 직후에 발행된 쿠폰이 중지되지 않고 남을 수 있다. 잠금은 트랜잭션이 끝날 때 풀린다.
     */
    void lockProductForShare(Long productId);

    /** 상품 여러 개의 현재 정보. 없는 ID만 빠진다 */
    List<ProductSnapshotDto> getProducts(Collection<Long> productIds);
}
