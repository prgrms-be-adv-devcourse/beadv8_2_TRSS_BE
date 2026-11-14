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
    // 이메일별로 시도를 세어, 잠금 시간 안에 최대 횟수를 넘으면 비밀번호를 확인하지 않고 429 LOGIN_LOCKED로 거절
    // 비밀번호를 확인하기 전에 횟수부터 올려서, 동시에 들어온 요청도 최대 횟수까지만 비밀번호를 확인함
    public AuthTokens login(String rawEmail, String password) {
        // 요청 DTO를 거치지 않는 호출도 회원 조회와 시도 횟수 키가 같은 이메일 기준이 되도록 여기서도 정규화함
        String email = Member.normalizeEmail(rawEmail);
        long attempt = loginAttemptStore.increaseAttempt(email, memberPolicy.loginLockTtl());
        if (attempt > memberPolicy.loginMaxAttempts()) {
            throw new DomainException(MemberErrorCode.LOGIN_LOCKED);
        }

        Member member = memberRepository.findByEmail(email)
                .filter(Member::isActive)
                .filter(m -> passwordEncoder.matches(password, m.getPassword()))
                .orElseThrow(() -> loginFailed(email, attempt));

        loginAttemptStore.deleteAttempts(email);
        return memberTokenIssuer.issue(member);
    }

    // 이번 시도가 최대 횟수째면 잠금을 시작 (잠금 시간이 이 시점부터 흐름)
    private DomainException loginFailed(String email, long attempt) {
        if (attempt == memberPolicy.loginMaxAttempts()) {
            loginAttemptStore.lock(email, memberPolicy.loginLockTtl());
            return new DomainException(MemberErrorCode.LOGIN_LOCKED);
        }
        return new DomainException(MemberErrorCode.LOGIN_FAILED);
    }
}
