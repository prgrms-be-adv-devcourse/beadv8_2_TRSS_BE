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
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.MemberStatus;

@ExtendWith(MockitoExtension.class)
class MemberGetMyInfoUseCaseTest {

    @Mock
    private MemberRepository memberRepository;

    @InjectMocks
    private MemberGetMyInfoUseCase memberGetMyInfoUseCase;

    private final Member member = Member.signUp("user@palette.com", "encoded", "홍길동");

    @Test
    @DisplayName("로그인 회원의 ID, 이메일, 이름, 역할을 돌려준다")
    void getMyInfo_success() {
        ReflectionTestUtils.setField(member, "id", 1L);
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));

        MyInfoDto myInfo = memberGetMyInfoUseCase.getMyInfo(1L);

        assertThat(myInfo.memberId()).isEqualTo(1L);
        assertThat(myInfo.email()).isEqualTo("user@palette.com");
        assertThat(myInfo.name()).isEqualTo("홍길동");
        assertThat(myInfo.role()).isEqualTo(MemberRole.USER);
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
