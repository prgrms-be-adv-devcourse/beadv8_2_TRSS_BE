package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.MemberPolicy;
import com.backend.boundedContext.member.out.LoginAttemptStore;
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

    @Mock
    private LoginAttemptStore loginAttemptStore;

    // 로그인 최대 실패 5회, 잠금 15분
    @Spy
    private MemberPolicy memberPolicy = new MemberPolicy(5, 60, 30, 5, 5, 15, 14, false, "Lax");

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
        verify(loginAttemptStore).deleteAttempts(EMAIL);
    }

    @Test
    @DisplayName("요청 DTO를 거치지 않고 대소문자·앞뒤 공백이 섞인 이메일이 와도 정규화한 이메일로 조회하고 시도 횟수를 센다")
    void login_normalizesEmail() {
        AuthTokens tokens = new AuthTokens("access", "refresh");
        given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(member));
        given(passwordEncoder.matches("palette123!", "encoded")).willReturn(true);
        given(memberTokenIssuer.issue(member)).willReturn(tokens);

        assertThat(memberLoginUseCase.login("  " + EMAIL.toUpperCase(Locale.ROOT) + " ", "palette123!")).isEqualTo(tokens);
        verify(loginAttemptStore).increaseAttempt(EMAIL, memberPolicy.loginLockTtl());
        verify(loginAttemptStore).deleteAttempts(EMAIL);
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

    @Test
    @DisplayName("비밀번호를 확인하기 전에 이메일별 시도 횟수를 올린다 (없는 이메일도 같이 센다)")
    void login_failed_increasesAttempt() {
        given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.empty());

        assertLoginFailed("palette123!");
        verify(loginAttemptStore).increaseAttempt(EMAIL, memberPolicy.loginLockTtl());
        verify(loginAttemptStore, never()).lock(anyString(), any());
        verify(loginAttemptStore, never()).deleteAttempts(anyString());
    }

    @Test
    @DisplayName("5번째 시도가 실패하면 잠금을 시작하고 429 LOGIN_LOCKED")
    void login_fifthFailure_locks() {
        given(loginAttemptStore.increaseAttempt(EMAIL, memberPolicy.loginLockTtl())).willReturn(5L);
        given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(member));
        given(passwordEncoder.matches("wrong123!", "encoded")).willReturn(false);

        assertLoginError("wrong123!", MemberErrorCode.LOGIN_LOCKED);
        verify(loginAttemptStore).lock(EMAIL, memberPolicy.loginLockTtl());
    }

    @Test
    @DisplayName("최대 횟수를 넘은 시도는 비밀번호를 확인하지 않고 429 LOGIN_LOCKED (맞는 비밀번호여도 거절, 잠금 시간은 늘리지 않음)")
    void login_locked() {
        given(loginAttemptStore.increaseAttempt(EMAIL, memberPolicy.loginLockTtl())).willReturn(6L);

        assertLoginError("palette123!", MemberErrorCode.LOGIN_LOCKED);
        verify(memberRepository, never()).findByEmail(anyString());
        verify(passwordEncoder, never()).matches(any(), any());
        verify(loginAttemptStore, never()).lock(anyString(), any());
    }

    private void assertLoginFailed(String password) {
        assertLoginError(password, MemberErrorCode.LOGIN_FAILED);
    }

    private void assertLoginError(String password, MemberErrorCode errorCode) {
        assertThatThrownBy(() -> memberLoginUseCase.login(EMAIL, password))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode")
                .isEqualTo(errorCode.resultCode());

        verify(memberTokenIssuer, never()).issue(any());
    }
}
