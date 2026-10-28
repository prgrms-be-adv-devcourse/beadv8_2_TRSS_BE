package com.backend.boundedContext.member.app.dto;

import java.time.LocalDateTime;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberType;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.MemberStatus;
import com.backend.shared.member.dto.SellerStatus;

// 관리자 회원 목록 항목
public record AdminMemberDto(
        Long memberId,
        String email,
        String name,
        MemberRole role,
        MemberType memberType,  // 일반 회원·판매자(판매 중)·관리자
        MemberStatus status,
        LocalDateTime createdAt,
        SellerStatus sellerStatus  // 판매자 신청 이력이 없으면 null
) {
    public static AdminMemberDto from(Member member, SellerStatus sellerStatus) {
        return new AdminMemberDto(member.getId(), member.getEmail(), member.getName(), member.getRole(),
                MemberType.of(member.getRole(), sellerStatus), member.getStatus(),
                member.getCreatedAt(), sellerStatus);
    }
}
