package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.SocialProvider;
import com.backend.boundedContext.member.out.kakao.KakaoOAuthClient;
import com.backend.boundedContext.member.out.kakao.KakaoUser;
import com.backend.global.exception.DomainException;

@ExtendWith(MockitoExtension.class)
class MemberLinkKakaoUseCaseTest {

    @Mock
    private KakaoOAuthClient kakaoOAuthClient;

    @Mock
    private MemberSocialAccountLinker memberSocialAccountLinker;

    @InjectMocks
    private MemberLinkKakaoUseCase useCase;

    @Test
    @DisplayName("인가 코드로 받은 카카오 회원번호를 로그인한 회원에게 프로필 이미지와 함께 연결한다")
    void link() {
        String imageUrl = "https://k.kakaocdn.net/dn/abc/img_640x640.jpg";
        given(kakaoOAuthClient.getUser("auth-code")).willReturn(new KakaoUser("4011223344", imageUrl));

        useCase.link(3L, "auth-code");

        verify(memberSocialAccountLinker).link(3L, SocialProvider.KAKAO, "4011223344", imageUrl);
    }

    @Test
    @DisplayName("카카오가 인가 코드를 거절하면 400 KAKAO_AUTH_FAILED이고 연결을 시도하지 않는다")
    void link_kakaoAuthFailed() {
        given(kakaoOAuthClient.getUser("used-code")).willThrow(new DomainException(MemberErrorCode.KAKAO_AUTH_FAILED));

        assertThatThrownBy(() -> useCase.link(3L, "used-code"))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-KAKAO_AUTH_FAILED");
        verify(memberSocialAccountLinker, never()).link(any(), any(), any(), any());
    }
}
