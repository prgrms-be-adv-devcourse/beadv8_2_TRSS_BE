package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.out.EmailVerificationStore;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.MemberStatus;
import com.backend.shared.payment.out.PaymentApi;

@ExtendWith(MockitoExtension.class)
class MemberSignUpUseCaseTest {

    private static final String EMAIL = "new@palette.com";
    private static final String PASSWORD = "palette123!";
    private static final String NAME = "홍길동";
    private static final String TOKEN = "verified-token";

    @Mock
    private MemberCheckEmailUseCase memberCheckEmailUseCase;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private EmailVerificationStore emailVerificationStore;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private PaymentApi paymentApi;

    @InjectMocks
    private MemberSignUpUseCase memberSignUpUseCase;

    @Test
    @DisplayName("인증된 이메일이면 암호화한 비밀번호로 USER·ACTIVE 회원을 저장하고 지갑을 만든 뒤 토큰을 지운다")
    void signUp_success() {
        given(emailVerificationStore.findVerifiedEmail(TOKEN)).willReturn(Optional.of(EMAIL));
        given(passwordEncoder.encode(PASSWORD)).willReturn("encoded");
        given(memberRepository.save(any(Member.class))).willAnswer(invocation -> {
            Member member = invocation.getArgument(0);
            ReflectionTestUtils.setField(member, "id", 1L);
            return member;
        });

        Long memberId = memberSignUpUseCase.signUp(EMAIL, PASSWORD, NAME, TOKEN);

        assertThat(memberId).isEqualTo(1L);

        ArgumentCaptor<Member> saved = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo(EMAIL);
        assertThat(saved.getValue().getPassword()).isEqualTo("encoded");
        assertThat(saved.getValue().getName()).isEqualTo(NAME);
        assertThat(saved.getValue().getRole()).isEqualTo(MemberRole.USER);
        assertThat(saved.getValue().getStatus()).isEqualTo(MemberStatus.ACTIVE);

        verify(paymentApi).createWallet(1L);
        verify(emailVerificationStore).deleteVerifiedToken(TOKEN);
    }

    @Test
    @DisplayName("인증 완료 토큰이 없거나 만료됐으면 400 EMAIL_NOT_VERIFIED를 던진다")
    void signUp_tokenNotFound() {
        given(emailVerificationStore.findVerifiedEmail(TOKEN)).willReturn(Optional.empty());

        assertThatThrownBy(() -> memberSignUpUseCase.signUp(EMAIL, PASSWORD, NAME, TOKEN))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode")
                .isEqualTo(MemberErrorCode.EMAIL_NOT_VERIFIED.resultCode());

        verify(memberRepository, never()).save(any());
        verify(paymentApi, never()).createWallet(anyLong());
    }

    @Test
    @DisplayName("인증한 이메일과 가입 이메일이 다르면 400 EMAIL_NOT_VERIFIED를 던진다")
    void signUp_emailMismatch() {
        given(emailVerificationStore.findVerifiedEmail(TOKEN)).willReturn(Optional.of("other@palette.com"));

        assertThatThrownBy(() -> memberSignUpUseCase.signUp(EMAIL, PASSWORD, NAME, TOKEN))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode")
                .isEqualTo(MemberErrorCode.EMAIL_NOT_VERIFIED.resultCode());

        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("이미 가입된 이메일이면 저장하지 않고 409 EMAIL_DUPLICATED를 던진다")
    void signUp_duplicated() {
        given(emailVerificationStore.findVerifiedEmail(TOKEN)).willReturn(Optional.of(EMAIL));
        willThrow(new DomainException(MemberErrorCode.EMAIL_DUPLICATED))
                .given(memberCheckEmailUseCase).checkEmail(EMAIL);

        assertThatThrownBy(() -> memberSignUpUseCase.signUp(EMAIL, PASSWORD, NAME, TOKEN))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode")
                .isEqualTo(MemberErrorCode.EMAIL_DUPLICATED.resultCode());

        verify(memberRepository, never()).save(any());
        verify(emailVerificationStore, never()).deleteVerifiedToken(anyString());
    }

    @Test
    @DisplayName("동시 가입으로 unique 제약에 걸리면 409 EMAIL_DUPLICATED로 바꿔 던진다")
    void signUp_concurrentDuplicated() {
        given(emailVerificationStore.findVerifiedEmail(TOKEN)).willReturn(Optional.of(EMAIL));
        given(passwordEncoder.encode(PASSWORD)).willReturn("encoded");
        given(memberRepository.save(any(Member.class))).willThrow(new DataIntegrityViolationException("uk_member_email"));

        assertThatThrownBy(() -> memberSignUpUseCase.signUp(EMAIL, PASSWORD, NAME, TOKEN))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode")
                .isEqualTo(MemberErrorCode.EMAIL_DUPLICATED.resultCode());

        verify(paymentApi, never()).createWallet(anyLong());
    }
}
