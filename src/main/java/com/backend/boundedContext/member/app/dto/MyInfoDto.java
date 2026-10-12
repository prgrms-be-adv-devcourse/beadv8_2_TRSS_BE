package com.backend.boundedContext.member.app.dto;

import java.time.LocalDateTime;

import com.backend.boundedContext.member.domain.Member;
import com.backend.shared.member.dto.MemberRole;

// 내 정보 조회 결과
public record MyInfoDto(
        Long memberId,
        String email,
        String name,
        MemberRole role,
        LocalDateTime createdAt
) {
    public static MyInfoDto from(Member member) {
        return new MyInfoDto(member.getId(), member.getEmail(), member.getName(), member.getRole(), member.getCreatedAt());
    }
}
