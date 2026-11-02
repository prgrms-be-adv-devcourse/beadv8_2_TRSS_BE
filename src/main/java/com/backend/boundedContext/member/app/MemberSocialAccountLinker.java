package com.backend.boundedContext.member.app;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.MemberSocialAccount;
import com.backend.boundedContext.member.domain.SocialProvider;
import com.backend.boundedContext.member.out.MemberSocialAccountRepository;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;

/**
 * 소셜 계정 연결의 DB 트랜잭션 구간
 * 카카오 API 호출(트랜잭션 밖)이 끝난 뒤 짧은 트랜잭션으로 저장하려고 UseCase와 별도 빈으로 분리함
 * (같은 클래스 내부 호출은 Spring 프록시를 거치지 않아 @Transactional이 적용되지 않음)
 */
@Component
@RequiredArgsConstructor
public class MemberSocialAccountLinker {

    private static final String MEMBER_PROVIDER_UNIQUE = "uk_member_social_account_member_provider";

    private final MemberSupport memberSupport;
    private final MemberSocialAccountRepository memberSocialAccountRepository;

    /**
     * 로그인한 회원에게 소셜 계정을 연결함
     * - 같은 회원에 같은 계정이 이미 연결돼 있으면 그대로 성공 (카카오 쪽에서 연결을 끊었다가 다시 잇는 경우 등)
     * - 다른 회원에 연결된 계정이면 409 SOCIAL_ACCOUNT_LINKED_TO_OTHER
     * - 이 회원에 같은 제공자의 다른 계정이 연결돼 있으면 409 SOCIAL_PROVIDER_ALREADY_LINKED (해제 후 다시 연결)
     */
    @Transactional
    public void link(Long memberId, SocialProvider provider, String providerUserId) {
        Member member = memberSupport.getLoginMember(memberId);   // 탈퇴 직후 남은 Access Token이면 401

        var linked = memberSocialAccountRepository.findByProviderAndProviderUserId(provider, providerUserId);
        if (linked.isPresent()) {
            if (linked.get().getMember().getId().equals(memberId)) return;
            throw new DomainException(MemberErrorCode.SOCIAL_ACCOUNT_LINKED_TO_OTHER);
        }
        if (memberSocialAccountRepository.findByMemberIdAndProvider(memberId, provider).isPresent()) {
            throw new DomainException(MemberErrorCode.SOCIAL_PROVIDER_ALREADY_LINKED);
        }

        try {
            memberSocialAccountRepository.saveAndFlush(MemberSocialAccount.link(member, provider, providerUserId));
        } catch (DataIntegrityViolationException e) {
            // 조회와 저장 사이에 같은 요청이 동시에 들어와 UNIQUE에 걸림 → 어느 제약인지로 위와 같은 오류를 돌려줌
            throw new DomainException(violates(e, MEMBER_PROVIDER_UNIQUE) ? MemberErrorCode.SOCIAL_PROVIDER_ALREADY_LINKED : MemberErrorCode.SOCIAL_ACCOUNT_LINKED_TO_OTHER, e);
        }
    }

    /**
     * 회원의 소셜 계정 연결을 해제함. 연결돼 있지 않아도 성공 (여러 번 눌러도 결과가 같음)
     * 카카오 쪽 연결(unlink API)은 끊지 않고 우리 DB의 연결 정보만 지움
     */
    @Transactional
    public void unlink(Long memberId, SocialProvider provider) {
        memberSupport.getLoginMember(memberId);
        memberSocialAccountRepository.findByMemberIdAndProvider(memberId, provider).ifPresent(memberSocialAccountRepository::delete);
    }

    private static boolean violates(DataIntegrityViolationException e, String constraintName) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.contains(constraintName);
    }
}
