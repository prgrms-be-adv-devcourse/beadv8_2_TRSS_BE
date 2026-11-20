package com.backend.boundedContext.member.out.kakao;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

// 카카오 인증 서버 오류 응답 본문 (예: {"error":"invalid_grant","error_description":"authorization code not found for code=...","error_code":"KOE320"})
@JsonIgnoreProperties(ignoreUnknown = true)
record KakaoErrorResponse(
        String error,
        @JsonProperty("error_code") String errorCode
) {}
