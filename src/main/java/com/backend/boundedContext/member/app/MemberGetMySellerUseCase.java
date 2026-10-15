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

// 내 판매자 정보 조회. 반려·철회됐으면 가장 최근 이력의 사유가 같이 보인다
@Component
@RequiredArgsConstructor
public class MemberGetMySellerUseCase {
    private final SellerRepository sellerRepository;
    private final SellerStatusHistoryRepository sellerStatusHistoryRepository;
    private final SettlementApi settlementApi;

    public MySellerResponse getMySeller(Long memberId) {
        Seller seller = sellerRepository.findByMemberId(memberId)
                .orElseThrow(() -> new DomainException(MemberErrorCode.SELLER_NOT_FOUND));
        SellerStatusHistory latest = sellerStatusHistoryRepository.findFirstBySellerIdOrderByIdDesc(seller.getId())
                .orElse(null);
        return MySellerResponse.from(seller, latest, settlementApi.getUnsettledAmount(seller.getId()));
    }
}
