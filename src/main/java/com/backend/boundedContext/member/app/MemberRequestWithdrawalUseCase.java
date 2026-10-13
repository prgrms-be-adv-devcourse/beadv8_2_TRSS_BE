package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.domain.SellerStatusHistory;
import com.backend.boundedContext.member.in.dto.MySellerResponse;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.boundedContext.member.out.SellerStatusHistoryRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.settlement.out.SettlementApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 판매자 본인 철회 요청 (APPROVED → WITHDRAW_REQUESTED). 관리자가 승인해야 철회가 끝난다.
 * 아직 지급되지 않은 정산 금액이 있으면 요청할 수 없다.
 */
@Component
@RequiredArgsConstructor
public class MemberRequestWithdrawalUseCase {
    private final SellerRepository sellerRepository;
    private final SellerStatusHistoryRepository sellerStatusHistoryRepository;
    private final SettlementApi settlementApi;

    public MySellerResponse requestWithdrawal(Long memberId, String reason) {
        Seller seller = sellerRepository.findByMemberId(memberId)
                .orElseThrow(() -> new DomainException(MemberErrorCode.SELLER_NOT_FOUND));

        if (settlementApi.getUnsettledAmount(seller.getId()) > 0) {
            throw new DomainException(MemberErrorCode.UNSETTLED_AMOUNT_EXISTS);
        }

        // APPROVED가 아니면 409 SELLER_STATE_INVALID. seller는 조회한 엔티티라 커밋 때 변경 감지로 저장된다
        SellerStatusHistory history = seller.requestWithdrawal(reason.strip());
        sellerStatusHistoryRepository.save(history);

        return MySellerResponse.from(seller, history);
    }
}
