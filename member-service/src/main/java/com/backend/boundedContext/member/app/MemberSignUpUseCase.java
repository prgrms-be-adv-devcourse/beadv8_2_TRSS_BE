package com.backend.boundedContext.member.app;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.out.EmailVerificationStore;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.event.MemberSignedUpEvent;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MemberSignUpUseCase {

    private final MemberCheckEmailUseCase memberCheckEmailUseCase;
    private final MemberRepository memberRepository;
    private final EmailVerificationStore emailVerificationStore;
    private final PasswordEncoder passwordEncoder;
    private final EventPublisher eventPublisher;

    // 인증 완료 토큰 확인 후 회원 저장, 가입 완료 이벤트 발행 (결제 컨텍스트가 받아 지갑 생성)
    public Long signUp(String email, String password, String name, String verificationToken) {
        validateVerifiedEmail(email, verificationToken);
        memberCheckEmailUseCase.checkEmail(email);

        Member member = saveMember(Member.signUp(email, passwordEncoder.encode(password), name));
        eventPublisher.publish(new MemberSignedUpEvent(member.getId()));

        emailVerificationStore.deleteVerifiedToken(verificationToken);  // 같은 토큰으로 다시 가입하지 못하도록 사용한 토큰 삭제
        return member.getId();
    }

    // 토큰이 없거나 만료됐거나, 인증한 이메일과 가입 이메일이 다르지는 않은지 검증
    private void validateVerifiedEmail(String email, String verificationToken) {
        String verifiedEmail = emailVerificationStore.findVerifiedEmail(verificationToken)
                .orElseThrow(() -> new DomainException(MemberErrorCode.EMAIL_NOT_VERIFIED));

        if (!verifiedEmail.equals(email)) {
            throw new DomainException(MemberErrorCode.EMAIL_NOT_VERIFIED);
        }
    }

    // 중복 확인 후 동시 요청으로 unique 제약에 걸리면 409 EMAIL_DUPLICATED
    private Member saveMember(Member member) {
        try {
            return memberRepository.save(member);
        } catch (DataIntegrityViolationException e) {
            throw new DomainException(MemberErrorCode.EMAIL_DUPLICATED, e);
        }
    }
}
