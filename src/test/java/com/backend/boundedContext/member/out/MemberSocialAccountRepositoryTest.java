package com.backend.boundedContext.member.out;

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
import org.springframework.dao.DataIntegrityViolationException;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberSocialAccount;
import com.backend.boundedContext.member.domain.SocialProvider;

/**
 * 소셜 계정 연결 테이블의 조회와 UNIQUE 제약을 실제 PostgreSQL로 확인한다. 로컬 Docker가 필요하다.
 */
@SpringBootTest
class MemberSocialAccountRepositoryTest {

    @Autowired
    private MemberSocialAccountRepository memberSocialAccountRepository;

    @Autowired
    private MemberRepository memberRepository;

    private final List<Member> members = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        members.forEach(member -> memberSocialAccountRepository.findByMemberIdAndProvider(member.getId(), SocialProvider.KAKAO)
                .ifPresent(memberSocialAccountRepository::delete));
        memberRepository.deleteAll(members);
    }

    @Test
    @DisplayName("연결한 소셜 계정은 제공자 회원 식별자로도, 회원 ID로도 찾을 수 있다")
    void find() {
        Member member = saveMember();
        String kakaoId = kakaoId();
        memberSocialAccountRepository.save(MemberSocialAccount.link(member, SocialProvider.KAKAO, kakaoId));

        assertThat(memberSocialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, kakaoId))
                .get().extracting(account -> account.getMember().getId()).isEqualTo(member.getId());
        assertThat(memberSocialAccountRepository.findByMemberIdAndProvider(member.getId(), SocialProvider.KAKAO))
                .get().extracting(MemberSocialAccount::getProviderUserId).isEqualTo(kakaoId);
        assertThat(memberSocialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, kakaoId()))
                .isEmpty();
    }

    @Test
    @DisplayName("같은 소셜 계정은 두 회원에게 연결할 수 없다 (DB UNIQUE)")
    void sameAccountToTwoMembers() {
        String kakaoId = kakaoId();
        memberSocialAccountRepository.save(MemberSocialAccount.link(saveMember(), SocialProvider.KAKAO, kakaoId));

        assertThatThrownBy(() -> memberSocialAccountRepository.saveAndFlush(
                MemberSocialAccount.link(saveMember(), SocialProvider.KAKAO, kakaoId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("한 회원은 같은 제공자 계정을 두 개 연결할 수 없다 (DB UNIQUE)")
    void twoAccountsOfSameProvider() {
        Member member = saveMember();
        memberSocialAccountRepository.save(MemberSocialAccount.link(member, SocialProvider.KAKAO, kakaoId()));

        assertThatThrownBy(() -> memberSocialAccountRepository.saveAndFlush(
                MemberSocialAccount.link(member, SocialProvider.KAKAO, kakaoId())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Member saveMember() {
        Member member = memberRepository.save(Member.signUp("social-it-" + UUID.randomUUID() + "@palette.com", "encoded", "회원"));
        members.add(member);
        return member;
    }

    private static String kakaoId() {
        return "test-" + UUID.randomUUID();
    }
}
