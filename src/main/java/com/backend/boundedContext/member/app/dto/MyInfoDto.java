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
        LocalDateTime createdAt,
        boolean kakaoLinked   // 카카오 계정 연결 여부 (연결하면 카카오로 로그인할 수 있음)
) {
    public static MyInfoDto of(Member member, boolean kakaoLinked) {
        return new MyInfoDto(member.getId(), member.getEmail(), member.getName(), member.getRole(), member.getCreatedAt(), kakaoLinked);
    }
}
