package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.MemberStatus;

@ExtendWith(MockitoExtension.class)
class MemberLoginUseCaseTest {

    private static final String EMAIL = "user@palette.com";

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private MemberTokenIssuer memberTokenIssuer;

    @InjectMocks
    private MemberLoginUseCase memberLoginUseCase;

    private final Member member = Member.signUp(EMAIL, "encoded", "홍길동");

    @Test
    @DisplayName("이메일·비밀번호가 맞으면 토큰을 발급한다")
    void login_success() {
        AuthTokens tokens = new AuthTokens("access", "refresh");
        given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(member));
        given(passwordEncoder.matches("palette123!", "encoded")).willReturn(true);
        given(memberTokenIssuer.issue(member)).willReturn(tokens);

        assertThat(memberLoginUseCase.login(EMAIL, "palette123!")).isEqualTo(tokens);
    }

    @Test
    @DisplayName("없는 이메일이면 401 LOGIN_FAILED")
    void login_emailNotFound() {
        given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.empty());

        assertLoginFailed("palette123!");
    }

    @Test
    @DisplayName("비밀번호가 틀리면 401 LOGIN_FAILED")
    void login_wrongPassword() {
        given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(member));
        given(passwordEncoder.matches("wrong123!", "encoded")).willReturn(false);

        assertLoginFailed("wrong123!");
    }

    @Test
    @DisplayName("탈퇴한 회원이면 401 LOGIN_FAILED")
    void login_deletedMember() {
        ReflectionTestUtils.setField(member, "status", MemberStatus.DELETED);
        given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(member));

        assertLoginFailed("palette123!");
    }

    private void assertLoginFailed(String password) {
        assertThatThrownBy(() -> memberLoginUseCase.login(EMAIL, password))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode")
                .isEqualTo(MemberErrorCode.LOGIN_FAILED.resultCode());

        verify(memberTokenIssuer, never()).issue(any());
    }
}
