package com.backend.boundedContext.member.app;

import java.util.List;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.app.dto.AdminMemberDetailDto;
import com.backend.boundedContext.member.domain.Member;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.order.dto.OrderSummaryDto;
import com.backend.shared.order.out.OrderApi;
import com.backend.shared.payment.dto.WalletSummaryDto;
import com.backend.shared.payment.out.PaymentApi;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MemberGetMemberDetailUseCase {

    private static final int RECENT_ORDER_LIMIT = 5;    // 관리자 화면에 보여 줄 최근 주문 수

    private final MemberSupport memberSupport;
    private final MemberGetSellerUseCase memberGetSellerUseCase;
    private final PaymentApi paymentApi;
    private final OrderApi orderApi;

    // 관리자 회원 상세: 회원 기본 정보 + 판매자 상태 + 지갑 + 최근 주문
    public AdminMemberDetailDto getMemberDetail(Long memberId) {
        Member member = memberSupport.getMember(memberId);
        SellerDto seller = memberGetSellerUseCase.findSellerByMemberId(memberId).orElse(null);
        WalletSummaryDto wallet = paymentApi.getWallet(memberId);
        List<OrderSummaryDto> recentOrders = orderApi.getRecentOrders(memberId, RECENT_ORDER_LIMIT);

        return AdminMemberDetailDto.of(member, seller, wallet, recentOrders);
    }
}
