package com.backend.boundedContext.member.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.app.dto.AdminSellerDetailDto;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.domain.SellerStatusHistory;
import com.backend.shared.product.out.ProductApi;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 철회 거절 (WITHDRAW_REQUESTED → APPROVED). 사유는 이력에 남고, 판매자는 다시 판매할 수 있다.
 * 철회 요청 때 중지된 상품을 다시 판매 중으로 되돌린다. 그때 취소된 결제 대기 주문은 되살리지 않는다.
 */
@Component
@RequiredArgsConstructor
public class MemberRejectWithdrawalUseCase {

    private final SellerSupport sellerSupport;
    private final ProductApi productApi;

    public AdminSellerDetailDto rejectWithdrawal(Long sellerId, String reason) {
        Seller seller = sellerSupport.getSeller(sellerId);

        // WITHDRAW_REQUESTED가 아니면 409 SELLER_STATE_INVALID
        SellerStatusHistory history = seller.rejectWithdrawal(reason.strip());

        // stoppedBy = SELLER_WITHDRAWN인 상품만 ON_SALE로 되돌린다. 관리자·판매 중지 요청으로 중지된 상품은 그대로
        productApi.resumeAllBySeller(sellerId);

        return sellerSupport.saveHistory(seller, history);
    }
}
