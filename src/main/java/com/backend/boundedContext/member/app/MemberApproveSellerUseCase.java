package com.backend.boundedContext.member.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.app.dto.AdminSellerDetailDto;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.domain.SellerStatusHistory;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 입점 승인 (PENDING → APPROVED). 회원 role은 USER 그대로 둔다.
 * 판매 가능 여부는 토큰이 아니라 요청마다 DB의 판매자 상태로 판단하므로, 토큰 재발급 없이 바로 판매 기능을 쓸 수 있다.
 */
@Component
@RequiredArgsConstructor
public class MemberApproveSellerUseCase {

    private final SellerSupport sellerSupport;

    public AdminSellerDetailDto approve(Long sellerId) {
        Seller seller = sellerSupport.getSeller(sellerId);

        // PENDING이 아니면 409 SELLER_STATE_INVALID
        SellerStatusHistory history = seller.approve();

        return sellerSupport.saveHistory(seller, history);
    }
}
