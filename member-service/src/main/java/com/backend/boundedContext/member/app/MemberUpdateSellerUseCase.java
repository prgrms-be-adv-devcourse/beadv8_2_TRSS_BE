package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.domain.SellerStatusHistory;
import com.backend.boundedContext.member.in.dto.MySellerResponse;
import com.backend.boundedContext.member.in.dto.SellerUpdateRequest;
import com.backend.boundedContext.member.out.MemberSettlementPort;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.boundedContext.member.out.SellerStatusHistoryRepository;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.event.SellerBusinessNameChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 판매자 정보 수정 (상호·연락처·정산 계좌). 판매 중(APPROVED)일 때만 가능
@Component
@RequiredArgsConstructor
public class MemberUpdateSellerUseCase {
    private final SellerRepository sellerRepository;
    private final SellerStatusHistoryRepository sellerStatusHistoryRepository;
    private final MemberSettlementPort memberSettlementPort;
    private final EventPublisher eventPublisher;

    public MySellerResponse update(Long memberId, SellerUpdateRequest request) {
        Seller seller = sellerRepository.findByMemberId(memberId)
                .orElseThrow(() -> new DomainException(MemberErrorCode.SELLER_NOT_FOUND));

        String oldBusinessName = seller.getBusinessName();
        seller.updateInfo(request.businessName(), request.phoneNumber());
        // 상호가 실제로 바뀔 때만 알린다 (연락처·계좌만 바꾸거나 같은 상호로 다시 저장하면 상품을 다시 색인할 필요가 없다)
        if (!seller.getBusinessName().equals(oldBusinessName)) {
            eventPublisher.publish(new SellerBusinessNameChangedEvent(seller.getId(), seller.getBusinessName()));
        }
        if (request.hasAccount()) {
            seller.changeAccount(request.bankName(), request.accountNumber(), request.accountHolder());
        }

        SellerStatusHistory latest = sellerStatusHistoryRepository.findFirstBySellerIdOrderByIdDesc(seller.getId())
                .orElse(null);
        // 미지급 금액은 화면 표시용이라, 정산을 조회하지 못하면 그 값만 비우고 응답한다
        return MySellerResponse.from(seller, latest, DisplayPortQueries.orNull(() -> memberSettlementPort.getUnsettledAmount(seller.getId())));
    }
}
