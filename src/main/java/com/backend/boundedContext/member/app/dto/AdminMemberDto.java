package com.backend.boundedContext.member.app.dto;

import java.time.LocalDateTime;

import com.backend.boundedContext.member.domain.Member;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.MemberStatus;
import com.backend.shared.member.dto.SellerStatus;

// 관리자 회원 목록 항목
public record AdminMemberDto(
        Long memberId,
        String email,
        String name,
        MemberRole role,
        MemberStatus status,
        LocalDateTime createdAt,
        SellerStatus sellerStatus  // 판매자 신청 이력이 없으면 null
) {
    public static AdminMemberDto from(Member member, SellerStatus sellerStatus) {
        return new AdminMemberDto(member.getId(), member.getEmail(), member.getName(), member.getRole(), member.getStatus(),
                member.getCreatedAt(), sellerStatus);
    }
}
