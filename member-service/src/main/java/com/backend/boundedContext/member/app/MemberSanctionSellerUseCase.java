package com.backend.boundedContext.member.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.app.dto.AdminSellerDetailDto;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.domain.SellerStatusHistory;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.shared.member.event.SellerSanctionedEvent;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 제재 (APPROVED·WITHDRAW_REQUESTED → SANCTIONED). 조건 없이 바로 끝나고, 다시 신청할 수 없다.
 * 회원 role은 USER 그대로 두고, 사업자번호도 남겨서 같은 번호로 다시 신청하는 것을 막는다.
 * 제재 이벤트(SellerSanctionedEvent)를 발행하면 상품(market)이 받아 판매 중 상품을 중지하고, 정산이 받아 정산을 보류한다.
 * 이미 결제된(PAID) 주문은 그대로 진행된다.
 */
@Component
@RequiredArgsConstructor
public class MemberSanctionSellerUseCase {

    private final SellerSupport sellerSupport;
    private final EventPublisher eventPublisher;

    public AdminSellerDetailDto sanction(Long sellerId, String reason) {
        Seller seller = sellerSupport.getSeller(sellerId);
        String strippedReason = reason.strip();

        // APPROVED·WITHDRAW_REQUESTED가 아니면 409 SELLER_STATE_INVALID
        SellerStatusHistory history = seller.sanction(strippedReason);

        // 커밋과 함께 outbox에 남고 릴레이가 seller.events로 보낸다.
        // 상품(market)이 받아 판매 중 상품을 모두 중지하고 그 상품이 든 결제 대기 주문을 취소한다.
        // 정산이 받아 지급 전 정산서(READY·FAILED·MANUAL_REQUIRED)를 HOLD로 바꾼다. 이벤트가 오기 전에 지급이 돌면 정산이 지급 직전에 판매자 상태를 보고 보류한다
        eventPublisher.publish(new SellerSanctionedEvent(sellerId, strippedReason));

        return sellerSupport.saveHistory(seller, history);
    }
}
