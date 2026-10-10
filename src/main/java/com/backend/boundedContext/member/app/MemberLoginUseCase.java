package com.backend.boundedContext.member.app;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MemberLoginUseCase {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final MemberTokenIssuer memberTokenIssuer;

    // 이메일·비밀번호 확인 후 토큰 발급 (없는 이메일·틀린 비밀번호·탈퇴 회원 모두 401 LOGIN_FAILED로 같게 응답)
    public AuthTokens login(String email, String password) {
        Member member = memberRepository.findByEmail(email)
                .filter(Member::isActive)
                .filter(m -> passwordEncoder.matches(password, m.getPassword()))
                .orElseThrow(() -> new DomainException(MemberErrorCode.LOGIN_FAILED));

        return memberTokenIssuer.issue(member);
    }
}
