package com.backend.global.security;

import com.backend.shared.member.dto.MemberRole;

/**
 * 로그인 회원 정보. 컨트롤러에서 {@code @AuthenticationPrincipal AuthMember authMember}로 꺼냄
 * 판매자 승인 상태처럼 바뀔 수 있는 값은 담지 않으므로, 판매 기능은 요청마다 DB에서 다시 확인함
 */
public record AuthMember(
        Long memberId,
        MemberRole role
) {}
