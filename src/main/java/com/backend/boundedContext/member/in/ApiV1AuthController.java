package com.backend.boundedContext.member.in;

import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backend.boundedContext.member.app.AuthTokens;
import com.backend.boundedContext.member.app.MemberFacade;
import com.backend.boundedContext.member.in.dto.AccessTokenResponse;
import com.backend.boundedContext.member.in.dto.EmailCodeVerifyRequest;
import com.backend.boundedContext.member.in.dto.EmailCodeVerifyResponse;
import com.backend.boundedContext.member.in.dto.EmailRequest;
import com.backend.boundedContext.member.in.dto.LoginRequest;
import com.backend.boundedContext.member.in.dto.SignUpRequest;
import com.backend.boundedContext.member.in.dto.SignUpResponse;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.global.rsData.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "인증", description = "이메일 인증, 회원가입, 로그인")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class ApiV1AuthController {

    private final MemberFacade memberFacade;
    private final RefreshTokenCookie refreshTokenCookie;

    @Operation(summary = "이메일 중복 확인")
    @PostMapping("/email/check")
    public RsData<Void> checkEmail(@Valid @RequestBody EmailRequest request) {
        memberFacade.checkEmail(request.email());
        return RsData.of("200-1", "사용할 수 있는 이메일입니다.");
    }

    @Operation(summary = "이메일 인증코드 발송")
    @PostMapping("/email/code")
    public RsData<Void> sendEmailCode(@Valid @RequestBody EmailRequest request) {
        memberFacade.sendEmailCode(request.email());
        return RsData.of("200-1", "인증코드를 발송했습니다.");
    }

    @Operation(summary = "이메일 인증코드 확인")
    @PostMapping("/email/verify")
    public RsData<EmailCodeVerifyResponse> verifyEmailCode(@Valid @RequestBody EmailCodeVerifyRequest request) {
        String token = memberFacade.verifyEmailCode(request.email(), request.code());
        return RsData.of("200-1", "이메일 인증이 완료되었습니다.", new EmailCodeVerifyResponse(token));
    }

    @Operation(summary = "회원가입")
    @PostMapping("/signup")
    public RsData<SignUpResponse> signUp(@Valid @RequestBody SignUpRequest request) {
        Long memberId = memberFacade.signUp(request.email(), request.password(), request.name(), request.verificationToken());
        return RsData.of("200-1", "회원가입이 완료되었습니다.", new SignUpResponse(memberId));
    }

    @Operation(summary = "로그인", description = "Access Token은 응답 바디, Refresh Token은 HttpOnly 쿠키로 발급")
    @PostMapping("/login")
    public RsData<AccessTokenResponse> login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        AuthTokens tokens = memberFacade.login(request.email(), request.password());
        refreshTokenCookie.add(response, tokens.refreshToken());
        return RsData.of("200-1", "로그인되었습니다.", new AccessTokenResponse(tokens.accessToken()));
    }

    @Operation(summary = "토큰 재발급", description = "쿠키의 Refresh Token으로 Access·Refresh Token 재발급 (Refresh Token 회전)")
    @PostMapping("/refresh")
    public RsData<AccessTokenResponse> refresh(
            @CookieValue(name = RefreshTokenCookie.NAME, required = false) String refreshToken,
            HttpServletResponse response
    ) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new DomainException(GlobalErrorCode.AUTH_TOKEN_EXPIRED);
        }
        AuthTokens tokens = memberFacade.refresh(refreshToken);
        refreshTokenCookie.add(response, tokens.refreshToken());
        return RsData.of("200-1", "토큰이 재발급되었습니다.", new AccessTokenResponse(tokens.accessToken()));
    }

    @Operation(summary = "로그아웃", description = "Refresh Token 폐기 및 쿠키 삭제")
    @PostMapping("/logout")
    public RsData<Void> logout(
            @CookieValue(name = RefreshTokenCookie.NAME, required = false) String refreshToken,
            HttpServletResponse response
    ) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            memberFacade.logout(refreshToken);
        }
        refreshTokenCookie.clear(response);
        return RsData.of("200-1", "로그아웃되었습니다.");
    }
}
