package com.backend.boundedContext.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.test.util.ReflectionTestUtils;

import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.MemberStatus;

class MemberTest {

    @Test
    @DisplayName("grantSeller: 일반 회원(USER)은 SELLER가 된다")
    void grantSeller_user() {
        Member member = Member.signUp("buyer@palette.test", "encoded", "구매자");

        member.grantSeller();

        assertThat(member.getRole()).isEqualTo(MemberRole.SELLER);
    }

    @Test
    @DisplayName("grantSeller: 이미 SELLER(철회 뒤 재승인)나 ADMIN이면 그대로다")
    void grantSeller_keep() {
        Member seller = Member.seller("seller@palette.test", "encoded", "판매자");
        Member admin = Member.admin("admin@palette.test", "encoded", "관리자");

        seller.grantSeller();
        admin.grantSeller();

        assertThat(seller.getRole()).isEqualTo(MemberRole.SELLER);
        assertThat(admin.getRole()).isEqualTo(MemberRole.ADMIN);
    }

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
