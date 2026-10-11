package com.backend.boundedContext.member.in.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 인증코드 확인 요청
public record EmailCodeVerifyRequest(
        @Email @NotBlank @Size(max = 100)
        String email,

        @NotBlank @Pattern(regexp = "\\d{6}", message = "인증코드는 숫자 6자리입니다.")
        String code
) {}
