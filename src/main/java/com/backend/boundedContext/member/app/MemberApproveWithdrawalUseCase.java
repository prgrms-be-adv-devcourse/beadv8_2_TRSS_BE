package com.backend.boundedContext.member.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.app.dto.AdminSellerDetailDto;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.domain.SellerStatusHistory;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.order.out.OrderApi;
import com.backend.shared.settlement.out.SettlementApi;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 철회 승인 (WITHDRAW_REQUESTED → WITHDRAWN). 회원 role은 SELLER 그대로 두고, 재신청할 수 있다.
 * 철회를 요청한 뒤에도 상품은 계속 팔리므로, 승인할 때 진행 중인 판매와 미정산 금액을 다시 확인한다.
 * 신청서는 이력 스냅샷에 남기고 사업자번호는 비운다.
 */
@Component
@RequiredArgsConstructor
public class MemberApproveWithdrawalUseCase {

    private final SellerSupport sellerSupport;
    private final OrderApi orderApi;
    private final SettlementApi settlementApi;

    public AdminSellerDetailDto approveWithdrawal(Long sellerId) {
        Seller seller = sellerSupport.getSeller(sellerId);

        // 다른 컨텍스트를 확인하기 전에 상태부터 본다 (철회 요청이 없는 판매자에게 판매·정산 오류가 나가지 않게)
        if (seller.getStatus() != SellerStatus.WITHDRAW_REQUESTED) {
            throw new DomainException(MemberErrorCode.SELLER_STATE_INVALID);
        }

        // 판매자 상품이 들어간 CREATED·PAYMENT_FAILED·PAID·RETURN_REQUESTED 주문
        if (orderApi.hasActiveSales(sellerId)) {
            throw new DomainException(MemberErrorCode.ACTIVE_SALES_EXIST);
        }
        if (settlementApi.getUnsettledAmount(sellerId) > 0) {
            throw new DomainException(MemberErrorCode.UNSETTLED_AMOUNT_EXISTS);
        }

        SellerStatusHistory history = seller.approveWithdrawal();

        // TODO: 판매자 상품 전체 판매 중지. ProductApi.stopAllBySeller(sellerId, reason)가 추가되면 같은 트랜잭션에서 호출한다 (상품 담당)

        return sellerSupport.saveHistory(seller, history);
    }
}
