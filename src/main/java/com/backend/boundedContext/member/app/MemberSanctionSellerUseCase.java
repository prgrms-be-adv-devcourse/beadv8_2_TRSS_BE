package com.backend.boundedContext.member.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.app.dto.AdminSellerDetailDto;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.domain.SellerStatusHistory;
import com.backend.shared.product.out.ProductApi;
import com.backend.shared.settlement.out.SettlementApi;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 제재 (APPROVED·WITHDRAW_REQUESTED → SANCTIONED). 조건 없이 바로 끝나고, 다시 신청할 수 없다.
 * 회원 role은 SELLER 그대로 두고, 사업자번호도 남겨서 같은 번호로 다시 신청하는 것을 막는다.
 * 후속 처리는 같은 트랜잭션에서 동기로 호출한다 (shared 계약): 상품 판매 중지 → 정산 보류.
 * 이미 결제된(PAID) 주문은 그대로 진행된다.
 */
@Component
@RequiredArgsConstructor
public class MemberSanctionSellerUseCase {

    private final SellerSupport sellerSupport;
    private final ProductApi productApi;
    private final SettlementApi settlementApi;

    public AdminSellerDetailDto sanction(Long sellerId, String reason) {
        Seller seller = sellerSupport.getSeller(sellerId);
        String strippedReason = reason.strip();

        // APPROVED·WITHDRAW_REQUESTED가 아니면 409 SELLER_STATE_INVALID
        SellerStatusHistory history = seller.sanction(strippedReason);

        // 판매 중 상품을 모두 중지하고 그 상품이 든 결제 대기 주문을 취소·재고 복원한다 (상품 담당, 내부에서 OrderApi.cancelPendingOrders 호출).
        // 철회 요청 때 이미 중지된 상품은 건드리지 않는다
        productApi.stopAllBySeller(sellerId, strippedReason);

        // 지급 전 정산서(READY·FAILED·MANUAL_REQUIRED)를 HOLD로 바꾸고, 이후 정산서도 HOLD로 만든다 (정산 담당)
        settlementApi.holdSeller(sellerId, strippedReason);

        return sellerSupport.saveHistory(seller, history);
    }
}
