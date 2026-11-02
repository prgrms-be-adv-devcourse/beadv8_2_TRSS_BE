package com.backend.boundedContext.member.out;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backend.boundedContext.member.domain.MemberSocialAccount;
import com.backend.boundedContext.member.domain.SocialProvider;

public interface MemberSocialAccountRepository extends JpaRepository<MemberSocialAccount, Long> {

    // 소셜 로그인: 제공자 회원 식별자로 연결된 계정 조회
    Optional<MemberSocialAccount> findByProviderAndProviderUserId(SocialProvider provider, String providerUserId);

    // 회원에게 연결된 제공자 계정 조회 (연결·해제, 내 정보 표시)
    Optional<MemberSocialAccount> findByMemberIdAndProvider(Long memberId, SocialProvider provider);

    // 내 정보의 연결 여부 표시
    boolean existsByMemberIdAndProvider(Long memberId, SocialProvider provider);
}
