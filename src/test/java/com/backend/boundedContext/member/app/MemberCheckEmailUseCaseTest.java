package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.global.exception.DomainException;

@ExtendWith(MockitoExtension.class)
class MemberCheckEmailUseCaseTest {

    @Mock
    private MemberRepository memberRepository;

    @InjectMocks
    private MemberCheckEmailUseCase memberCheckEmailUseCase;

    @Test
    @DisplayName("가입되지 않은 이메일이면 통과한다")
    void checkEmail_available() {
        given(memberRepository.existsByEmail("new@palette.com")).willReturn(false);

        assertThatCode(() -> memberCheckEmailUseCase.checkEmail("new@palette.com"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("이미 가입된 이메일이면 409 EMAIL_DUPLICATED를 던진다")
    void checkEmail_duplicated() {
        given(memberRepository.existsByEmail("used@palette.com")).willReturn(true);

        assertThatThrownBy(() -> memberCheckEmailUseCase.checkEmail("used@palette.com"))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode")
                .isEqualTo(MemberErrorCode.EMAIL_DUPLICATED.resultCode());
    }
}
