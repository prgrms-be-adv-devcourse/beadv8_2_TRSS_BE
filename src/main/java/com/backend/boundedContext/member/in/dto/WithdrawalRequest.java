package com.backend.boundedContext.member.in.dto;

import jakarta.validation.constraints.NotBlank;

// 회원 탈퇴 요청 (본인 확인용 비밀번호)
public record WithdrawalRequest(
        @NotBlank
        String password
) {}
