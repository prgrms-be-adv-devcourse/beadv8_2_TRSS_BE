package com.backend.boundedContext.member.in.dto;

// 인증코드 확인 응답 (회원가입 요청에 담아 보낼 인증 완료 토큰)
public record EmailCodeVerifyResponse(
        String verificationToken
) {}
