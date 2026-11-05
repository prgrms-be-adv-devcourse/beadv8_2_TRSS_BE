package com.backend.boundedContext.member.in.dto;

// 내부 API 이메일로 회원 조회 요청. 이메일이 URL·접근 로그에 남지 않도록 본문으로 받음
// 형식 검증은 하지 않음 (MemberApi.findMemberByEmail처럼 형식이 맞지 않으면 없음으로 응답)
public record MemberEmailSearchRequest(
        String email
) {}
