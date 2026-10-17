package com.backend.boundedContext.member.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.app.dto.AdminSellerDetailDto;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.domain.SellerStatusHistory;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 철회 거절 (WITHDRAW_REQUESTED → APPROVED). 사유는 이력에 남고, 판매자는 다시 판매할 수 있다.
 */
@Component
@RequiredArgsConstructor
public class MemberRejectWithdrawalUseCase {

    private final SellerSupport sellerSupport;

    public AdminSellerDetailDto rejectWithdrawal(Long sellerId, String reason) {
        Seller seller = sellerSupport.getSeller(sellerId);

        // WITHDRAW_REQUESTED가 아니면 409 SELLER_STATE_INVALID
        SellerStatusHistory history = seller.rejectWithdrawal(reason.strip());

        return sellerSupport.saveHistory(seller, history);
    }
}
