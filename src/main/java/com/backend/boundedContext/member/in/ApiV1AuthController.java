package com.backend.boundedContext.member.in;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backend.boundedContext.member.app.MemberFacade;
import com.backend.boundedContext.member.in.dto.EmailCodeVerifyRequest;
import com.backend.boundedContext.member.in.dto.EmailCodeVerifyResponse;
import com.backend.boundedContext.member.in.dto.EmailRequest;
import com.backend.boundedContext.member.in.dto.SignUpRequest;
import com.backend.boundedContext.member.in.dto.SignUpResponse;
import com.backend.global.rsData.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "인증", description = "이메일 인증, 회원가입")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class ApiV1AuthController {

    private final MemberFacade memberFacade;

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
}
