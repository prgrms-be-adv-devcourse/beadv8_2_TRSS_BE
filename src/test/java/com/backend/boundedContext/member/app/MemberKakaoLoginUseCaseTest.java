package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.MemberSocialAccount;
import com.backend.boundedContext.member.domain.SocialProvider;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.member.out.MemberSocialAccountRepository;
import com.backend.boundedContext.member.out.kakao.KakaoOAuthClient;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.MemberStatus;

@ExtendWith(MockitoExtension.class)
class MemberKakaoLoginUseCaseTest {

    private static final String KAKAO_USER_ID = "4011223344";

    @Mock
    private KakaoOAuthClient kakaoOAuthClient;

    @Mock
    private MemberSocialAccountRepository memberSocialAccountRepository;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private MemberTokenIssuer memberTokenIssuer;

    @InjectMocks
    private MemberKakaoLoginUseCase useCase;

    private final Member member = Member.signUp("user@palette.com", "encoded", "홍길동");

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(member, "id", 3L);
        given(kakaoOAuthClient.getUserId("auth-code")).willReturn(KAKAO_USER_ID);
    }

    @Test
    @DisplayName("카카오 회원번호에 연결된 회원으로 토큰을 발급한다")
    void login() {
        AuthTokens tokens = new AuthTokens("access", "refresh");
        given(memberSocialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, KAKAO_USER_ID))
                .willReturn(Optional.of(MemberSocialAccount.link(member, SocialProvider.KAKAO, KAKAO_USER_ID)));
        given(memberRepository.findById(3L)).willReturn(Optional.of(member));
        given(memberTokenIssuer.issue(member)).willReturn(tokens);

        assertThat(useCase.login("auth-code")).isEqualTo(tokens);
    }

    @Test
    @DisplayName("연결된 회원이 없으면 가입시키지 않고 401 SOCIAL_NOT_LINKED")
    void login_notLinked() {
        given(memberSocialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, KAKAO_USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.login("auth-code"))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo(MemberErrorCode.SOCIAL_NOT_LINKED.resultCode());
        verify(memberRepository, never()).findById(anyLong());
        verify(memberTokenIssuer, never()).issue(any());
    }

    @Test
    @DisplayName("연결된 회원이 탈퇴했으면 이메일 로그인처럼 401 LOGIN_FAILED")
    void login_withdrawnMember() {
        ReflectionTestUtils.setField(member, "status", MemberStatus.DELETED);
        given(memberSocialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, KAKAO_USER_ID))
                .willReturn(Optional.of(MemberSocialAccount.link(member, SocialProvider.KAKAO, KAKAO_USER_ID)));
        given(memberRepository.findById(3L)).willReturn(Optional.of(member));

        assertThatThrownBy(() -> useCase.login("auth-code"))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo(MemberErrorCode.LOGIN_FAILED.resultCode());
        verify(memberTokenIssuer, never()).issue(any());
    }
}
