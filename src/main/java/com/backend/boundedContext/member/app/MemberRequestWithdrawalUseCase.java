package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.domain.SellerStatusHistory;
import com.backend.boundedContext.member.in.dto.MySellerResponse;
import com.backend.boundedContext.member.out.MemberProductPort;
import com.backend.boundedContext.member.out.MemberSettlementPort;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.boundedContext.member.out.SellerStatusHistoryRepository;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.event.SellerWithdrawalRequestedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 판매자 본인 철회 요청 (APPROVED → WITHDRAW_REQUESTED). 관리자가 승인해야 철회가 끝난다.
 * 아직 지급되지 않은 정산 금액이 있으면 요청할 수 없다. 정산을 조회하지 못하면 503 WITHDRAWAL_CHECK_UNAVAILABLE로 거부한다.
 * 요청하면 판매 중 상품을 모두 중지한다 (철회가 거절되면 다시 판매 중으로 돌아온다).
 * 철회 요청 이벤트(SellerWithdrawalRequestedEvent)도 함께 발행한다. 상품이 컨슈머로 받게 되면 동기 호출은 뺀다.
 */
@Component
@RequiredArgsConstructor
public class MemberRequestWithdrawalUseCase {
    private final SellerRepository sellerRepository;
    private final SellerStatusHistoryRepository sellerStatusHistoryRepository;
    private final MemberSettlementPort memberSettlementPort;
    private final MemberProductPort memberProductPort;
    private final EventPublisher eventPublisher;

    public MySellerResponse requestWithdrawal(Long memberId, String reason) {
        Seller seller = sellerRepository.findByMemberId(memberId)
                .orElseThrow(() -> new DomainException(MemberErrorCode.SELLER_NOT_FOUND));

        long unsettledAmount = WithdrawalCheckQueries.query(() -> memberSettlementPort.getUnsettledAmount(seller.getId()));
        if (unsettledAmount > 0) {
            throw new DomainException(MemberErrorCode.UNSETTLED_AMOUNT_EXISTS);
        }

        // APPROVED가 아니면 409 SELLER_STATE_INVALID. seller는 조회한 엔티티라 커밋 때 변경 감지로 저장된다
        String strippedReason = reason.strip();
        SellerStatusHistory history = seller.requestWithdrawal(strippedReason);
        sellerStatusHistoryRepository.save(history);

        // ON_SALE 상품을 STOPPED(SELLER_WITHDRAWN)로 바꾸고, 그 상품이 든 결제 대기 주문을 취소한다 (상품 담당, 같은 트랜잭션)
        memberProductPort.stopAllBySeller(seller.getId(), strippedReason);

        // 커밋과 함께 outbox에 남고 릴레이가 seller.events로 보낸다. 상품 중지는 다시 해도 결과가 같다
        eventPublisher.publish(new SellerWithdrawalRequestedEvent(seller.getId(), strippedReason));

        return MySellerResponse.from(seller, history, unsettledAmount);
    }
}
