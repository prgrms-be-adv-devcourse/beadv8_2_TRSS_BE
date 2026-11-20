package com.backend.boundedContext.member.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MemberCheckEmailUseCase {

    private final MemberRepository memberRepository;

    // 가입 가능한 이메일인지 확인
    public void checkEmail(String email) {
        if (memberRepository.existsByEmail(email)) {
            throw new DomainException(MemberErrorCode.EMAIL_DUPLICATED);
        }
    }
}
