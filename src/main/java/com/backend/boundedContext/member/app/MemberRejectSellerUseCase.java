package com.backend.boundedContext.member.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.app.dto.AdminSellerDetailDto;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.domain.SellerStatusHistory;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 입점 반려 (PENDING → REJECTED). 사유는 이력에 남는다.
 * 신청서는 이력 스냅샷에 남기고 사업자번호는 비워서, 다른 회원이 그 번호로 신청할 수 있게 한다.
 */
@Component
@RequiredArgsConstructor
public class MemberRejectSellerUseCase {

    private final SellerSupport sellerSupport;

    public AdminSellerDetailDto reject(Long sellerId, String reason) {
        Seller seller = sellerSupport.getSeller(sellerId);

        // PENDING이 아니면 409 SELLER_STATE_INVALID
        SellerStatusHistory history = seller.reject(reason.strip());

        return sellerSupport.saveHistory(seller, history);
    }
}
