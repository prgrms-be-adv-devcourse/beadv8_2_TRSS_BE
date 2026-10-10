package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.MemberPolicy;
import com.backend.boundedContext.member.out.EmailVerificationStore;
import com.backend.global.exception.DomainException;

@ExtendWith(MockitoExtension.class)
class MemberVerifyEmailCodeUseCaseTest {

    private static final String EMAIL = "new@palette.com";
    private static final String CODE = "123456";

    @Mock
    private EmailVerificationStore emailVerificationStore;

    private MemberVerifyEmailCodeUseCase memberVerifyEmailCodeUseCase;

    @BeforeEach
    void setUp() {
        MemberPolicy memberPolicy = new MemberPolicy(5, 60, 30, 5);
        memberVerifyEmailCodeUseCase = new MemberVerifyEmailCodeUseCase(emailVerificationStore, memberPolicy);
    }

    @Test
    @DisplayName("코드가 일치하면 코드와 실패 횟수를 지우고 30분짜리 인증 완료 토큰을 발급한다")
    void verifyEmailCode_success() {
        given(emailVerificationStore.findCode(EMAIL)).willReturn(Optional.of(CODE));

        String token = memberVerifyEmailCodeUseCase.verifyEmailCode(EMAIL, CODE);

        assertThat(token).isNotBlank();
        verify(emailVerificationStore).deleteCode(EMAIL);
        verify(emailVerificationStore).deleteFailCount(EMAIL);
        verify(emailVerificationStore).saveVerifiedToken(token, EMAIL, Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("코드가 없으면(만료) 400 CODE_EXPIRED를 던진다")
    void verifyEmailCode_expired() {
        given(emailVerificationStore.findCode(EMAIL)).willReturn(Optional.empty());

        assertThatThrownBy(() -> memberVerifyEmailCodeUseCase.verifyEmailCode(EMAIL, CODE))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode")
                .isEqualTo(MemberErrorCode.CODE_EXPIRED.resultCode());

        verify(emailVerificationStore, never()).saveVerifiedToken(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("코드가 다르고 실패 횟수가 5회 미만이면 400 CODE_INVALID를 던지고 코드는 유지한다")
    void verifyEmailCode_invalid() {
        given(emailVerificationStore.findCode(EMAIL)).willReturn(Optional.of(CODE));
        given(emailVerificationStore.increaseFailCount(EMAIL, Duration.ofMinutes(5))).willReturn(4L);

        assertThatThrownBy(() -> memberVerifyEmailCodeUseCase.verifyEmailCode(EMAIL, "000000"))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode")
                .isEqualTo(MemberErrorCode.CODE_INVALID.resultCode());

        verify(emailVerificationStore, never()).deleteCode(anyString());
        verify(emailVerificationStore, never()).saveVerifiedToken(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("5회째 틀리면 코드와 실패 횟수를 지우고 400 CODE_ATTEMPTS_EXCEEDED를 던진다")
    void verifyEmailCode_attemptsExceeded() {
        given(emailVerificationStore.findCode(EMAIL)).willReturn(Optional.of(CODE));
        given(emailVerificationStore.increaseFailCount(EMAIL, Duration.ofMinutes(5))).willReturn(5L);

        assertThatThrownBy(() -> memberVerifyEmailCodeUseCase.verifyEmailCode(EMAIL, "000000"))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode")
                .isEqualTo(MemberErrorCode.CODE_ATTEMPTS_EXCEEDED.resultCode());

        verify(emailVerificationStore).deleteCode(EMAIL);
        verify(emailVerificationStore).deleteFailCount(EMAIL);
        verify(emailVerificationStore, never()).saveVerifiedToken(anyString(), anyString(), any());
    }
}
