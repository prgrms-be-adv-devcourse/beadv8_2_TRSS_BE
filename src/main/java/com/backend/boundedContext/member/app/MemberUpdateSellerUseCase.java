package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.domain.SellerStatusHistory;
import com.backend.boundedContext.member.in.dto.MySellerResponse;
import com.backend.boundedContext.member.in.dto.SellerUpdateRequest;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.boundedContext.member.out.SellerStatusHistoryRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.settlement.out.SettlementApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 판매자 정보 수정 (상호·연락처·정산 계좌). 판매 중(APPROVED)일 때만 가능
@Component
@RequiredArgsConstructor
public class MemberUpdateSellerUseCase {
    private final SellerRepository sellerRepository;
    private final SellerStatusHistoryRepository sellerStatusHistoryRepository;
    private final SettlementApi settlementApi;

    public MySellerResponse update(Long memberId, SellerUpdateRequest request) {
        Seller seller = sellerRepository.findByMemberId(memberId)
                .orElseThrow(() -> new DomainException(MemberErrorCode.SELLER_NOT_FOUND));

        seller.updateInfo(request.businessName(), request.phoneNumber());
        if (request.hasAccount()) {
            seller.changeAccount(request.bankName(), request.accountNumber(), request.accountHolder());
        }

        SellerStatusHistory latest = sellerStatusHistoryRepository.findFirstBySellerIdOrderByIdDesc(seller.getId())
                .orElse(null);
        return MySellerResponse.from(seller, latest, settlementApi.getUnsettledAmount(seller.getId()));
    }
}
