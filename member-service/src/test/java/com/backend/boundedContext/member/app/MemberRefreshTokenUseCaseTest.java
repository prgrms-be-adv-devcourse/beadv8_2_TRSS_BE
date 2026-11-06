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
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.member.out.RefreshTokenStore;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.shared.member.dto.MemberStatus;

@ExtendWith(MockitoExtension.class)
class MemberRefreshTokenUseCaseTest {

    @Mock
    private RefreshTokenStore refreshTokenStore;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private MemberTokenIssuer memberTokenIssuer;

    @InjectMocks
    private MemberRefreshTokenUseCase memberRefreshTokenUseCase;

    private final Member member = Member.signUp("user@palette.com", "encoded", "홍길동");

    @Test
    @DisplayName("유효한 Refresh Token이면 꺼내서(폐기) 새 토큰을 발급한다")
    void refresh_success() {
        AuthTokens tokens = new AuthTokens("new-access", "new-refresh");
        given(refreshTokenStore.consume("old-refresh")).willReturn(Optional.of(1L));
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));
        given(memberTokenIssuer.issue(member)).willReturn(tokens);

        assertThat(memberRefreshTokenUseCase.refresh("old-refresh")).isEqualTo(tokens);
    }

    @Test
    @DisplayName("없거나 만료·폐기된 Refresh Token이면 401 AUTH_TOKEN_EXPIRED")
    void refresh_tokenNotFound() {
        given(refreshTokenStore.consume("old-refresh")).willReturn(Optional.empty());

        assertExpired();
    }

    @Test
    @DisplayName("탈퇴한 회원이면 401 AUTH_TOKEN_EXPIRED")
    void refresh_deletedMember() {
        ReflectionTestUtils.setField(member, "status", MemberStatus.DELETED);
        given(refreshTokenStore.consume("old-refresh")).willReturn(Optional.of(1L));
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));

        assertExpired();
    }

    private void assertExpired() {
        assertThatThrownBy(() -> memberRefreshTokenUseCase.refresh("old-refresh"))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode")
                .isEqualTo(GlobalErrorCode.AUTH_TOKEN_EXPIRED.resultCode());

        verify(memberTokenIssuer, never()).issue(any());
    }
}
