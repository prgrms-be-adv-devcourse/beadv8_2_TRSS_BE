package com.backend.boundedContext.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.test.util.ReflectionTestUtils;

import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.MemberStatus;

class MemberTest {

    @Test
    @DisplayName("withdraw: DELETED로 바꾸고 이메일·이름·비밀번호를 비식별화한다")
    void withdraw() {
        Member member = Member.signUp("buyer@palette.test", "encoded", "구매자");
        ReflectionTestUtils.setField(member, "id", 7L);

        member.withdraw();

        assertThat(member.getStatus()).isEqualTo(MemberStatus.DELETED);
        assertThat(member.getDeletedAt()).isNotNull();
        assertThat(member.getEmail()).isEqualTo("deleted-7@deleted.palette");
        assertThat(member.getName()).isEqualTo(Member.WITHDRAWN_NAME);
        assertThat(member.getPassword()).isNotEqualTo("encoded");
    }

    @Test
    @DisplayName("withdraw: 이미 탈퇴한 회원이면 404 MEMBER_NOT_FOUND")
    void withdraw_alreadyDeleted() {
        Member member = Member.signUp("buyer@palette.test", "encoded", "구매자");
        ReflectionTestUtils.setField(member, "id", 7L);
        member.withdraw();

        assertThatThrownBy(member::withdraw)
                .isInstanceOf(DomainException.class)
                .extracting("resultCode")
                .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND.resultCode());
    }
}
