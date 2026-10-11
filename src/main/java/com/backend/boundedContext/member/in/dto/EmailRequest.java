package com.backend.boundedContext.member.in.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 이메일 중복 확인, 인증코드 발송 요청
public record EmailRequest(
        @Email @NotBlank @Size(max = 100)
        String email
) {}
