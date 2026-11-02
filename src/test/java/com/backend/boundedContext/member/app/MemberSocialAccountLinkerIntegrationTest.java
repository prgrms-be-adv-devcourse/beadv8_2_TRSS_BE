package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.SocialProvider;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.member.out.MemberSocialAccountRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.ErrorCode;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.shared.member.dto.MemberStatus;

/**
 * 소셜 계정 연결·해제 규칙을 실제 PostgreSQL로 확인한다. 로컬 Docker가 필요하다.
 */
@SpringBootTest
class MemberSocialAccountLinkerIntegrationTest {

    @Autowired
    private MemberSocialAccountLinker linker;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private MemberSocialAccountRepository memberSocialAccountRepository;

    private final List<Member> members = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        members.forEach(member -> memberSocialAccountRepository.findByMemberIdAndProvider(member.getId(), SocialProvider.KAKAO)
                .ifPresent(memberSocialAccountRepository::delete));
        memberRepository.deleteAll(members);
    }

    @Test
    @DisplayName("연결: 회원에게 카카오 계정이 연결되고, 같은 계정을 다시 연결해도 성공한다")
    void link_andRelinkSameAccount() {
        Member member = saveMember();
        String kakaoId = kakaoId();

        linker.link(member.getId(), SocialProvider.KAKAO, kakaoId);
        linker.link(member.getId(), SocialProvider.KAKAO, kakaoId);   // 카카오 쪽에서 연결을 끊었다 다시 잇는 경우

        assertThat(memberSocialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, kakaoId))
                .get().extracting(account -> account.getMember().getId()).isEqualTo(member.getId());
    }

    @Test
    @DisplayName("연결: 다른 회원에 연결된 카카오 계정이면 409 SOCIAL_ACCOUNT_LINKED_TO_OTHER")
    void link_accountLinkedToOther() {
        String kakaoId = kakaoId();
        linker.link(saveMember().getId(), SocialProvider.KAKAO, kakaoId);
        Long otherMemberId = saveMember().getId();

        assertError(() -> linker.link(otherMemberId, SocialProvider.KAKAO, kakaoId), MemberErrorCode.SOCIAL_ACCOUNT_LINKED_TO_OTHER);
        assertThat(memberSocialAccountRepository.existsByMemberIdAndProvider(otherMemberId, SocialProvider.KAKAO)).isFalse();
    }

    @Test
    @DisplayName("연결: 이미 다른 카카오 계정이 연결된 회원이면 409 SOCIAL_PROVIDER_ALREADY_LINKED (해제 후에는 연결 가능)")
    void link_providerAlreadyLinked() {
        Member member = saveMember();
        linker.link(member.getId(), SocialProvider.KAKAO, kakaoId());
        String newKakaoId = kakaoId();

        assertError(() -> linker.link(member.getId(), SocialProvider.KAKAO, newKakaoId), MemberErrorCode.SOCIAL_PROVIDER_ALREADY_LINKED);

        linker.unlink(member.getId(), SocialProvider.KAKAO);
        linker.link(member.getId(), SocialProvider.KAKAO, newKakaoId);
        assertThat(memberSocialAccountRepository.findByMemberIdAndProvider(member.getId(), SocialProvider.KAKAO))
                .get().extracting(account -> account.getProviderUserId()).isEqualTo(newKakaoId);
    }

    @Test
    @DisplayName("해제: 연결 정보가 지워지고, 연결돼 있지 않아도 성공한다")
    void unlink() {
        Member member = saveMember();
        linker.link(member.getId(), SocialProvider.KAKAO, kakaoId());

        linker.unlink(member.getId(), SocialProvider.KAKAO);
        linker.unlink(member.getId(), SocialProvider.KAKAO);

        assertThat(memberSocialAccountRepository.existsByMemberIdAndProvider(member.getId(), SocialProvider.KAKAO)).isFalse();
    }

    @Test
    @DisplayName("탈퇴한 회원(남은 Access Token)은 연결할 수 없다 (401 UNAUTHORIZED)")
    void link_withdrawnMember() {
        Member member = saveMember();
        ReflectionTestUtils.setField(member, "status", MemberStatus.DELETED);
        memberRepository.save(member);

        assertError(() -> linker.link(member.getId(), SocialProvider.KAKAO, kakaoId()), GlobalErrorCode.UNAUTHORIZED);
    }

    private Member saveMember() {
        Member member = memberRepository.save(Member.signUp("social-link-it-" + UUID.randomUUID() + "@palette.com", "encoded", "회원"));
        members.add(member);
        return member;
    }

    private static String kakaoId() {
        return "test-" + UUID.randomUUID();
    }

    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ErrorCode errorCode) {
        assertThatThrownBy(action).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.getResultCode()).isEqualTo(errorCode.resultCode()));
    }
}
