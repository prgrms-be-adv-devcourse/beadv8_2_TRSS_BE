package com.backend.boundedContext.member.app;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.app.dto.WithdrawalCheckDto;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.order.out.OrderApi;
import com.backend.shared.payment.out.PaymentApi;
import com.backend.shared.settlement.out.SettlementApi;

import lombok.RequiredArgsConstructor;

/**
 * 회원 탈퇴 조건 확인. 탈퇴 조건 조회와 탈퇴 요청이 같은 기준을 쓰도록 한 곳에 둔다
 */
@Component
@RequiredArgsConstructor
public class MemberWithdrawalSupport {

    // 판매자 철회를 먼저 해야 하는 상태 (반려·철회 완료·제재는 판매가 끝난 상태라 탈퇴를 막지 않는다)
    private static final Set<SellerStatus> SELLER_ACTIVE_STATUSES =
            EnumSet.of(SellerStatus.PENDING, SellerStatus.APPROVED, SellerStatus.WITHDRAW_REQUESTED);

    private final SellerRepository sellerRepository;
    private final OrderApi orderApi;
    private final SettlementApi settlementApi;
    private final PaymentApi paymentApi;

    // 진행 중인 주문, 판매자로서 진행 중인 판매·정산받지 않은 판매 금액(판매자만), 남은 예치금, 결과 대기 중인 충전 건, 판매자 상태를 확인
    public WithdrawalCheckDto check(Long memberId) {
        Optional<Seller> seller = sellerRepository.findByMemberId(memberId);

        boolean hasActiveOrders = orderApi.hasActiveOrders(memberId);
        boolean hasActiveSales = seller.map(s -> orderApi.hasActiveSales(s.getId())).orElse(false);  // 판매자 정보가 있으면 상태와 무관하게 확인 (제재된 판매자도 구매확정 전 판매가 남아 있으면, 탈퇴 뒤 정산 후보가 생김)
        long unsettledAmount = seller.map(s -> settlementApi.getUnsettledAmount(s.getId())).orElse(0L);
        long balance = paymentApi.getWallet(memberId).balance();

        // 결과 확인 중인 충전 건은 아직 잔액에 없지만, 탈퇴 뒤 대사 스케줄러가 예치금을 반영하면 돌려받을 수 없음
        boolean hasPendingTopups = paymentApi.hasPendingTopups(memberId);
        boolean sellerActive = seller.map(s -> SELLER_ACTIVE_STATUSES.contains(s.getStatus())).orElse(false);

        return WithdrawalCheckDto.of(hasActiveOrders, hasActiveSales, unsettledAmount, balance, hasPendingTopups, sellerActive);
    }
}
