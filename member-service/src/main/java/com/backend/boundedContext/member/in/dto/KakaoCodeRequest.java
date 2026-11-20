package com.backend.boundedContext.member.in.dto;

import jakarta.validation.constraints.NotBlank;

// 카카오 인가 코드 (프론트 콜백 화면이 카카오에게 받은 code. state는 프론트가 확인하고 보내지 않음)
public record KakaoCodeRequest(
        @NotBlank
        String code
) {}
