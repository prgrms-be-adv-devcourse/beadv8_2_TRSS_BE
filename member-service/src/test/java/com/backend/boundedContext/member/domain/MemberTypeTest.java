package com.backend.boundedContext.member.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.SellerStatus;

class MemberTypeTest {

    @Test
    @DisplayName("관리자는 관리자, 판매 중(APPROVED)인 회원은 판매자, 판매자 정보가 없는 회원은 일반 회원")
    void of() {
        assertThat(MemberType.of(MemberRole.ADMIN, null)).isEqualTo(MemberType.ADMIN);
        assertThat(MemberType.of(MemberRole.USER, SellerStatus.APPROVED)).isEqualTo(MemberType.SELLER);
        assertThat(MemberType.of(MemberRole.USER, null)).isEqualTo(MemberType.USER);
    }

    @ParameterizedTest
    @EnumSource(value = SellerStatus.class, names = "APPROVED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("판매 중이 아닌 판매자 상태(심사 중·반려·철회 심사 중·철회·제재)는 일반 회원")
    void of_notApproved(SellerStatus status) {
        assertThat(MemberType.of(MemberRole.USER, status)).isEqualTo(MemberType.USER);
    }
}
