package com.backend.boundedContext.member.app;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.MemberPolicy;
import com.backend.boundedContext.member.out.LoginAttemptStore;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MemberLoginUseCase {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final MemberTokenIssuer memberTokenIssuer;
    private final LoginAttemptStore loginAttemptStore;
    private final MemberPolicy memberPolicy;

    // 이메일·비밀번호 확인 후 토큰 발급 (없는 이메일·틀린 비밀번호·탈퇴 회원 모두 401 LOGIN_FAILED로 같게 응답)
    // 이메일별로 실패를 세어, 잠금 시간 안에 최대 횟수에 도달하면 비밀번호를 확인하지 않고 429 LOGIN_LOCKED로 거절
    public AuthTokens login(String email, String password) {
        if (loginAttemptStore.getFailCount(email) >= memberPolicy.loginMaxAttempts()) {
            throw new DomainException(MemberErrorCode.LOGIN_LOCKED);
        }

        Member member = memberRepository.findByEmail(email)
                .filter(Member::isActive)
                .filter(m -> passwordEncoder.matches(password, m.getPassword()))
                .orElseThrow(() -> loginFailed(email));

        loginAttemptStore.deleteFailCount(email);
        return memberTokenIssuer.issue(member);
    }

    // 실패 횟수를 올리고, 이번 실패로 최대 횟수에 도달하면 잠금을 시작
    private DomainException loginFailed(String email) {
        long failCount = loginAttemptStore.increaseFailCount(email, memberPolicy.loginLockTtl());

        if (failCount >= memberPolicy.loginMaxAttempts()) {
            loginAttemptStore.lock(email, memberPolicy.loginLockTtl());
            return new DomainException(MemberErrorCode.LOGIN_LOCKED);
        }
        return new DomainException(MemberErrorCode.LOGIN_FAILED);
    }
}
