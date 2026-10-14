package com.backend.boundedContext.member.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.shared.member.dto.MemberRole;

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
}
