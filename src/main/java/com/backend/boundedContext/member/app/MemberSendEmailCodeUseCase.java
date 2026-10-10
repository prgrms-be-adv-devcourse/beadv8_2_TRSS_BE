package com.backend.boundedContext.member.app;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.domain.MemberPolicy;
import com.backend.boundedContext.member.out.EmailVerificationStore;
import com.backend.boundedContext.member.out.MemberMailSender;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MemberSendEmailCodeUseCase {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final MemberCheckEmailUseCase memberCheckEmailUseCase;
    private final EmailVerificationStore emailVerificationStore;
    private final MemberMailSender memberMailSender;
    private final MemberPolicy memberPolicy;

    // 6자리 인증코드 발송
    public void sendEmailCode(String email) {
        memberCheckEmailUseCase.checkEmail(email);

        if (!emailVerificationStore.lockResend(email, memberPolicy.emailCodeResendInterval())) {
            throw new DomainException(GlobalErrorCode.TOO_MANY_REQUESTS);
        }

        String code = generateCode();
        emailVerificationStore.saveCode(email, code, memberPolicy.emailCodeTtl());
        emailVerificationStore.deleteFailCount(email);  // 새 코드는 실패 횟수 0부터 시작

        try {
            memberMailSender.sendVerificationCode(email, code, memberPolicy.emailCodeTtl().toMinutes());
        } catch (DomainException e) {
            // 발송 실패 시 바로 다시 요청할 수 있도록 저장한 코드와 재발송 제한을 되돌림
            emailVerificationStore.deleteCode(email);
            emailVerificationStore.unlockResend(email);
            throw e;
        }
    }

    private String generateCode() {
        return "%06d".formatted(RANDOM.nextInt(1_000_000));
    }
}
