package com.backend.boundedContext.member.out;

import org.springframework.stereotype.Component;

import com.backend.shared.product.out.ProductApi;

import lombok.RequiredArgsConstructor;

/**
 * 같은 앱의 ProductApi로 MemberProductPort를 구현함 (상품 모듈 분리 전까지 사용)
 */
@Component
@RequiredArgsConstructor
public class ProductApiMemberProductAdapter implements MemberProductPort {

    private final ProductApi productApi;

    @Override
    public void stopAllBySeller(Long sellerId, String reason) {
        productApi.stopAllBySeller(sellerId, reason);
    }

    @Override
    public void resumeAllBySeller(Long sellerId) {
        productApi.resumeAllBySeller(sellerId);
    }
}
