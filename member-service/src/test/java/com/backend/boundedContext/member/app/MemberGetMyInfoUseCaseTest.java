package com.backend.boundedContext.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.boundedContext.member.app.dto.MyInfoDto;
import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberSocialAccount;
import com.backend.boundedContext.member.domain.SocialProvider;
import com.backend.boundedContext.member.out.InfluencerRepository;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.member.out.MemberSocialAccountRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.MemberStatus;

@ExtendWith(MockitoExtension.class)
class MemberGetMyInfoUseCaseTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private MemberSocialAccountRepository memberSocialAccountRepository;

    @Mock
    private InfluencerRepository influencerRepository;

    @InjectMocks
    private MemberGetMyInfoUseCase memberGetMyInfoUseCase;

    private static final String IMAGE_URL = "https://k.kakaocdn.net/dn/abc/img_640x640.jpg";

    private final Member member = Member.signUp("user@palette.com", "encoded", "홍길동");

    @Test
    @DisplayName("로그인 회원의 ID, 이메일, 이름, 역할, 카카오 연결 여부와 프로필 이미지를 돌려준다")
    void getMyInfo_success() {
        ReflectionTestUtils.setField(member, "id", 1L);
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));
        given(memberSocialAccountRepository.findByMemberIdAndProvider(1L, SocialProvider.KAKAO))
                .willReturn(Optional.of(MemberSocialAccount.link(member, SocialProvider.KAKAO, "4011223344", IMAGE_URL)));

        MyInfoDto myInfo = memberGetMyInfoUseCase.getMyInfo(1L);

        assertThat(myInfo.memberId()).isEqualTo(1L);
        assertThat(myInfo.email()).isEqualTo("user@palette.com");
        assertThat(myInfo.name()).isEqualTo("홍길동");
        assertThat(myInfo.role()).isEqualTo(MemberRole.USER);
        assertThat(myInfo.kakaoLinked()).isTrue();
        assertThat(myInfo.profileImageUrl()).isEqualTo(IMAGE_URL);
        assertThat(myInfo.influencerStatus()).isNull();   // 인플루언서 신청 전
    }

    @Test
    @DisplayName("카카오를 연결하지 않았으면 연결 여부 false, 프로필 이미지 null")
    void getMyInfo_kakaoNotLinked() {
        ReflectionTestUtils.setField(member, "id", 1L);
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));
        given(memberSocialAccountRepository.findByMemberIdAndProvider(1L, SocialProvider.KAKAO)).willReturn(Optional.empty());

        MyInfoDto myInfo = memberGetMyInfoUseCase.getMyInfo(1L);

        assertThat(myInfo.kakaoLinked()).isFalse();
        assertThat(myInfo.profileImageUrl()).isNull();
    }

    @Test
    @DisplayName("카카오는 연결했지만 프로필 이미지에 동의하지 않았으면 연결 여부 true, 프로필 이미지 null")
    void getMyInfo_kakaoLinkedWithoutImage() {
        ReflectionTestUtils.setField(member, "id", 1L);
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));
        given(memberSocialAccountRepository.findByMemberIdAndProvider(1L, SocialProvider.KAKAO))
                .willReturn(Optional.of(MemberSocialAccount.link(member, SocialProvider.KAKAO, "4011223344", null)));

        MyInfoDto myInfo = memberGetMyInfoUseCase.getMyInfo(1L);

        assertThat(myInfo.kakaoLinked()).isTrue();
        assertThat(myInfo.profileImageUrl()).isNull();
    }

    @Test
    @DisplayName("회원이 없으면 401 UNAUTHORIZED")
    void getMyInfo_notFound() {
        given(memberRepository.findById(1L)).willReturn(Optional.empty());

        assertUnauthorized();
    }

    @Test
    @DisplayName("탈퇴한 회원이면 401 UNAUTHORIZED")
    void getMyInfo_deleted() {
        ReflectionTestUtils.setField(member, "status", MemberStatus.DELETED);
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));

        assertUnauthorized();
    }

    private void assertUnauthorized() {
        assertThatThrownBy(() -> memberGetMyInfoUseCase.getMyInfo(1L))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode")
                .isEqualTo(GlobalErrorCode.UNAUTHORIZED.resultCode());
    }
}
