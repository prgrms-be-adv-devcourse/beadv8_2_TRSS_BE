package com.backend.global.security;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import com.backend.global.exception.ErrorCode;
import com.backend.global.rsData.RsData;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * 필터 단계의 인증·권한 실패는 GlobalExceptionHandler에 닿지 않으므로 RsData 형식으로 응답을 직접 쓴다.
 * JwtAuthenticationEntryPoint(401), JwtAccessDeniedHandler(403)가 함께 사용
 */
@Component
@RequiredArgsConstructor
public class SecurityErrorResponseWriter {

    private final ObjectMapper objectMapper;

    public void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        write(response, errorCode.resultCode(), errorCode.msg());
    }

    public void write(HttpServletResponse response, String resultCode, String msg) throws IOException {
        RsData<Void> body = RsData.of(resultCode, msg);
        response.setStatus(body.statusCode());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
