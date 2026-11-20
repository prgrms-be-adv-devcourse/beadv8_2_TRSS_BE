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

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.global.exception.DomainException;

@ExtendWith(MockitoExtension.class)
class MemberSupportTest {

    @Mock
    private MemberRepository memberRepository;

    @InjectMocks
    private MemberSupport memberSupport;

    @Test
    @DisplayName("회원이 있으면 돌려준다")
    void getMember_found() {
        Member member = Member.signUp("user@palette.com", "encoded", "홍길동");
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));

        assertThat(memberSupport.getMember(1L)).isSameAs(member);
    }

    @Test
    @DisplayName("회원이 없으면 404 MEMBER_NOT_FOUND를 던진다")
    void getMember_notFound() {
        given(memberRepository.findById(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> memberSupport.getMember(1L))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode")
                .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND.resultCode());
    }
}
