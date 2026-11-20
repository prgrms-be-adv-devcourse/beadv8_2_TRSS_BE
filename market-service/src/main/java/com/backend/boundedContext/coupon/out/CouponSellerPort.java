package com.backend.boundedContext.coupon.out;

import java.util.Collection;
import java.util.Map;

/**
 * 쿠폰(협약, 상품 쿠폰)이 쓰는 판매자 기능 (member-service)
 */
public interface CouponSellerPort {

    /** 로그인한 회원의 판매 가능한(APPROVED) 판매자 ID. 판매 기능을 쓸 수 없는 상태면 403-SELLER_NOT_APPROVED */
    Long getApprovedSellerId(Long memberId);

    /** 판매자 ID → 상호명 (목록 표시용). 없는 ID는 결과에서 빠진다 */
    Map<Long, String> getBusinessNames(Collection<Long> sellerIds);
}
