package com.backend.boundedContext.member.in.dto;

import com.backend.boundedContext.member.domain.Member;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

// 로그인 요청
public record LoginRequest(
        @NotBlank @Email
        String email,

        @NotBlank
        String password
) {
    public LoginRequest {
        email = Member.normalizeEmail(email);
    }
}
