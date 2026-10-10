package com.backend.boundedContext.member.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MemberFacade {

    private final MemberCheckEmailUseCase memberCheckEmailUseCase;
    private final MemberSendEmailCodeUseCase memberSendEmailCodeUseCase;
    private final MemberVerifyEmailCodeUseCase memberVerifyEmailCodeUseCase;

    @Transactional(readOnly = true)
    public void checkEmail(String email) {
        memberCheckEmailUseCase.checkEmail(email);
    }

    public void sendEmailCode(String email) {
        memberSendEmailCodeUseCase.sendEmailCode(email);
    }

    // 인증 완료 토큰 반환
    public String verifyEmailCode(String email, String code) {
        return memberVerifyEmailCodeUseCase.verifyEmailCode(email, code);
    }
}
