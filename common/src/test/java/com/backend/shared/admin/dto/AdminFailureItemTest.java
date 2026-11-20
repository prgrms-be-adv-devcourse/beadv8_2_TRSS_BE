package com.backend.shared.admin.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminFailureItemTest {

    @Test
    @DisplayName("이메일은 아이디 앞 2글자만 남기고 가린다")
    void maskEmail() {
        assertThat(AdminFailureItem.maskEmail("buyer@palette.test")).isEqualTo("bu***@palette.test");
    }

    @Test
    @DisplayName("아이디가 2글자 이하이면 첫 글자만 남긴다")
    void maskShortEmail() {
        assertThat(AdminFailureItem.maskEmail("ab@palette.test")).isEqualTo("a***@palette.test");
        assertThat(AdminFailureItem.maskEmail("a@palette.test")).isEqualTo("a***@palette.test");
    }

    @Test
    @DisplayName("null이나 @가 없는 값은 그대로 돌려준다")
    void notEmail() {
        assertThat(AdminFailureItem.maskEmail(null)).isNull();
        assertThat(AdminFailureItem.maskEmail("seller")).isEqualTo("seller");
    }
}
