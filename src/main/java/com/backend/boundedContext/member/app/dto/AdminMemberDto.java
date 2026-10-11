package com.backend.boundedContext.member.app.dto;

import java.time.LocalDateTime;

import com.backend.boundedContext.member.domain.Member;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.MemberStatus;

// 관리자 회원 목록 항목
public record AdminMemberDto(
        Long memberId,
        String email,
        String name,
        MemberRole role,
        MemberStatus status,
        LocalDateTime createdAt
) {
    public static AdminMemberDto from(Member member) {
        return new AdminMemberDto(member.getId(), member.getEmail(), member.getName(), member.getRole(), member.getStatus(), member.getCreatedAt());
    }
}
