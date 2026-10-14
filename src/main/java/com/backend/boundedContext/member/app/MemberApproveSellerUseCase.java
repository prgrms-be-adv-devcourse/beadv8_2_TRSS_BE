package com.backend.boundedContext.member.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.app.dto.AdminSellerDetailDto;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.domain.SellerStatusHistory;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 입점 승인 (PENDING → APPROVED). 회원에게 SELLER 권한을 준다.
 * 새 권한은 다음 토큰 재발급 때 access token에 들어가고, 판매 가능 여부는 요청마다 DB의 판매자 상태로 판단한다.
 */
@Component
@RequiredArgsConstructor
public class MemberApproveSellerUseCase {

    private final SellerSupport sellerSupport;

    public AdminSellerDetailDto approve(Long sellerId) {
        Seller seller = sellerSupport.getSeller(sellerId);

        // PENDING이 아니면 409 SELLER_STATE_INVALID
        SellerStatusHistory history = seller.approve();
        seller.getMember().grantSeller();

        return sellerSupport.saveHistory(seller, history);
    }
}
