package com.backend.boundedContext.member.app;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.MemberPolicy;
import com.backend.boundedContext.member.out.EmailVerificationStore;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MemberVerifyEmailCodeUseCase {

    private final EmailVerificationStore emailVerificationStore;
    private final MemberPolicy memberPolicy;

    // 인증코드 확인 후 인증 완료 토큰 발급
    public String verifyEmailCode(String email, String code) {
        String savedCode = emailVerificationStore.findCode(email)
                .orElseThrow(() -> new DomainException(MemberErrorCode.CODE_EXPIRED));

        if (!savedCode.equals(code)) {
            handleFailure(email);
        }

        emailVerificationStore.deleteCode(email);
        emailVerificationStore.deleteFailCount(email);

        String token = UUID.randomUUID().toString();
        emailVerificationStore.saveVerifiedToken(token, email, memberPolicy.emailVerifiedTokenTtl());
        return token;
    }

    // 최대 실패 횟수에 도달하면 코드를 지워 재발송을 받도록 함
    private void handleFailure(String email) {
        long failCount = emailVerificationStore.increaseFailCount(email, memberPolicy.emailCodeTtl());

        if (failCount >= memberPolicy.emailCodeMaxAttempts()) {
            emailVerificationStore.deleteCode(email);
            emailVerificationStore.deleteFailCount(email);
            throw new DomainException(MemberErrorCode.CODE_ATTEMPTS_EXCEEDED);
        }
        throw new DomainException(MemberErrorCode.CODE_INVALID);
    }
}
