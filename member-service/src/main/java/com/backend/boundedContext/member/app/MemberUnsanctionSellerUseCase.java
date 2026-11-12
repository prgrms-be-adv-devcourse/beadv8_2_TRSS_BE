package com.backend.boundedContext.member.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.app.dto.AdminSellerDetailDto;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.domain.SellerStatusHistory;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.event.SellerUnsanctionedEvent;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 제재 해지 (SANCTIONED → APPROVED). 판매 자격을 돌려준다.
 * 해지 이벤트(SellerUnsanctionedEvent)를 발행해 정산·상품이 제재로 바꾼 것을 되돌릴 수 있게 한다(받는 쪽은 각 서비스가 맡는다).
 * 이벤트를 받지 않아도 판매자가 APPROVED라 관리자가 정산 보류를 직접 해제할 수 있다.
 */
@Component
@RequiredArgsConstructor
public class MemberUnsanctionSellerUseCase {

    private final SellerSupport sellerSupport;
    private final EventPublisher eventPublisher;

    public AdminSellerDetailDto unsanction(Long sellerId, String reason) {
        Seller seller = sellerSupport.getSeller(sellerId);
        // 제재된 판매자도 회원 탈퇴할 수 있다. 탈퇴하면 계좌 등이 비식별화돼 있어 판매자로 되살리면 안 된다
        if (!seller.getMember().isActive()) throw new DomainException(MemberErrorCode.SELLER_STATE_INVALID);

        String strippedReason = reason.strip();

        // SANCTIONED가 아니면 409 SELLER_STATE_INVALID
        SellerStatusHistory history = seller.unsanction(strippedReason);

        // 커밋과 함께 outbox에 남고 릴레이가 seller.events로 보낸다
        eventPublisher.publish(new SellerUnsanctionedEvent(sellerId, strippedReason));

        return sellerSupport.saveHistory(seller, history);
    }
}
