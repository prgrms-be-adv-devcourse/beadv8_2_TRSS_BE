package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.MemberPolicy;
import com.backend.boundedContext.member.out.EmailVerificationStore;
import com.backend.boundedContext.member.out.MemberMailSender;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;

@ExtendWith(MockitoExtension.class)
class MemberSendEmailCodeUseCaseTest {

    private static final String EMAIL = "new@palette.com";

    @Mock
    private MemberCheckEmailUseCase memberCheckEmailUseCase;

    @Mock
    private EmailVerificationStore emailVerificationStore;

    @Mock
    private MemberMailSender memberMailSender;

    private MemberSendEmailCodeUseCase memberSendEmailCodeUseCase;

    @BeforeEach
    void setUp() {
        MemberPolicy memberPolicy = new MemberPolicy(5, 60, 30);
        memberSendEmailCodeUseCase = new MemberSendEmailCodeUseCase(
                memberCheckEmailUseCase, emailVerificationStore, memberMailSender, memberPolicy);
    }

    @Test
    @DisplayName("6자리 인증코드를 5분 TTL로 저장하고 같은 코드를 메일로 발송한다")
    void sendEmailCode_success() {
        given(emailVerificationStore.lockResend(EMAIL, Duration.ofSeconds(60))).willReturn(true);

        memberSendEmailCodeUseCase.sendEmailCode(EMAIL);

        ArgumentCaptor<String> savedCode = ArgumentCaptor.forClass(String.class);
        verify(emailVerificationStore).saveCode(eq(EMAIL), savedCode.capture(), eq(Duration.ofMinutes(5)));
        assertThat(savedCode.getValue()).matches("\\d{6}");
        verify(memberMailSender).sendVerificationCode(EMAIL, savedCode.getValue(), 5);
    }

    @Test
    @DisplayName("이미 가입된 이메일이면 발송하지 않고 409 EMAIL_DUPLICATED를 던진다")
    void sendEmailCode_duplicated() {
        willThrow(new DomainException(MemberErrorCode.EMAIL_DUPLICATED))
                .given(memberCheckEmailUseCase).checkEmail(EMAIL);

        assertThatThrownBy(() -> memberSendEmailCodeUseCase.sendEmailCode(EMAIL))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode")
                .isEqualTo(MemberErrorCode.EMAIL_DUPLICATED.resultCode());

        verify(emailVerificationStore, never()).lockResend(anyString(), any());
        verify(memberMailSender, never()).sendVerificationCode(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("60초 안에 다시 요청하면 발송하지 않고 429 TOO_MANY_REQUESTS를 던진다")
    void sendEmailCode_tooManyRequests() {
        given(emailVerificationStore.lockResend(EMAIL, Duration.ofSeconds(60))).willReturn(false);

        assertThatThrownBy(() -> memberSendEmailCodeUseCase.sendEmailCode(EMAIL))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode")
                .isEqualTo(GlobalErrorCode.TOO_MANY_REQUESTS.resultCode());

        verify(emailVerificationStore, never()).saveCode(anyString(), anyString(), any());
        verify(memberMailSender, never()).sendVerificationCode(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("메일 발송에 실패하면 저장한 코드와 재발송 제한을 되돌리고 예외를 다시 던진다")
    void sendEmailCode_mailFailed() {
        given(emailVerificationStore.lockResend(EMAIL, Duration.ofSeconds(60))).willReturn(true);
        willThrow(new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR))
                .given(memberMailSender).sendVerificationCode(eq(EMAIL), anyString(), eq(5L));

        assertThatThrownBy(() -> memberSendEmailCodeUseCase.sendEmailCode(EMAIL))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode")
                .isEqualTo(GlobalErrorCode.EXTERNAL_SERVICE_ERROR.resultCode());

        verify(emailVerificationStore).deleteCode(EMAIL);
        verify(emailVerificationStore).unlockResend(EMAIL);
    }
}
