package com.backend.boundedContext.member.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.app.dto.AdminMemberDetailDto;
import com.backend.boundedContext.member.domain.Member;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.payment.dto.WalletSummaryDto;
import com.backend.shared.payment.out.PaymentApi;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MemberGetMemberDetailUseCase {

    private final MemberSupport memberSupport;
    private final MemberGetSellerUseCase memberGetSellerUseCase;
    private final PaymentApi paymentApi;

    // 관리자 회원 상세: 회원 기본 정보 + 판매자 상태 + 지갑
    public AdminMemberDetailDto getMemberDetail(Long memberId) {
        Member member = memberSupport.getMember(memberId);
        SellerDto seller = memberGetSellerUseCase.findSellerByMemberId(memberId).orElse(null);
        WalletSummaryDto wallet = paymentApi.getWallet(memberId);

        return AdminMemberDetailDto.of(member, seller, wallet);
    }
}
