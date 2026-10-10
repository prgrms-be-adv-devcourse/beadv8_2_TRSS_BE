package com.backend.boundedContext.member.in.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 회원가입 요청
public record SignUpRequest(
        @Email @NotBlank @Size(max = 100)
        String email,

        // 8~20자, 영문·숫자·특수문자 각 1자 이상 (ASCII만 허용, 한글·공백 불가)
        @NotBlank
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[!-/:-@\\[-`{-~])[!-~]{8,20}$", message = "비밀번호는 8~20자이며 영문, 숫자, 특수문자를 각각 1자 이상 포함해야 합니다.")
        String password,

        @NotBlank @Size(max = 20)
        String name,

        @NotBlank
        String verificationToken
) {
    public SignUpRequest {
        name = name == null ? null : name.trim();
    }
}
