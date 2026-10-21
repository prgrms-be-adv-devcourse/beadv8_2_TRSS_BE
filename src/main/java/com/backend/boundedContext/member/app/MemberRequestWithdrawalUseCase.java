package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.domain.SellerStatusHistory;
import com.backend.boundedContext.member.in.dto.MySellerResponse;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.boundedContext.member.out.SellerStatusHistoryRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.product.out.ProductApi;
import com.backend.shared.settlement.out.SettlementApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 판매자 본인 철회 요청 (APPROVED → WITHDRAW_REQUESTED). 관리자가 승인해야 철회가 끝난다.
 * 아직 지급되지 않은 정산 금액이 있으면 요청할 수 없다.
 * 요청하면 판매 중 상품을 모두 중지한다 (철회가 거절되면 다시 판매 중으로 돌아온다).
 */
@Component
@RequiredArgsConstructor
public class MemberRequestWithdrawalUseCase {
    private final SellerRepository sellerRepository;
    private final SellerStatusHistoryRepository sellerStatusHistoryRepository;
    private final SettlementApi settlementApi;
    private final ProductApi productApi;

    public MySellerResponse requestWithdrawal(Long memberId, String reason) {
        Seller seller = sellerRepository.findByMemberId(memberId)
                .orElseThrow(() -> new DomainException(MemberErrorCode.SELLER_NOT_FOUND));

        long unsettledAmount = settlementApi.getUnsettledAmount(seller.getId());
        if (unsettledAmount > 0) {
            throw new DomainException(MemberErrorCode.UNSETTLED_AMOUNT_EXISTS);
        }

        // APPROVED가 아니면 409 SELLER_STATE_INVALID. seller는 조회한 엔티티라 커밋 때 변경 감지로 저장된다
        String strippedReason = reason.strip();
        SellerStatusHistory history = seller.requestWithdrawal(strippedReason);
        sellerStatusHistoryRepository.save(history);

        // ON_SALE 상품을 STOPPED(SELLER_WITHDRAWN)로 바꾸고, 그 상품이 든 결제 대기 주문을 취소한다 (상품 담당, 같은 트랜잭션)
        productApi.stopAllBySeller(seller.getId(), strippedReason);

        return MySellerResponse.from(seller, history, unsettledAmount);
    }
}
