package com.backend.boundedContext.member.app.dto;

import java.time.LocalDateTime;

import com.backend.boundedContext.member.domain.Member;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.MemberStatus;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.payment.dto.WalletSummaryDto;

// 관리자 회원 상세 (최근 주문은 주문 컨텍스트 API가 정해지면 추가)
public record AdminMemberDetailDto(
        Long memberId,
        String email,
        String name,
        MemberRole role,
        MemberStatus status,
        LocalDateTime createdAt,
        SellerStatus sellerStatus,  // 판매자 신청 이력이 없으면 null
        Wallet wallet
) {
    // 지갑 잔액 (사용 가능 금액, 홀딩 중 금액)
    public record Wallet(
            long balance,
            long heldAmount
    ) {}

    public static AdminMemberDetailDto of(Member member, SellerDto seller, WalletSummaryDto wallet) {
        return new AdminMemberDetailDto(
                member.getId(), member.getEmail(), member.getName(), member.getRole(), member.getStatus(), member.getCreatedAt(),
                seller == null ? null : seller.status(),
                new Wallet(wallet.balance(), wallet.heldAmount())
        );
    }
}
