package com.backend.boundedContext.coupon.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CouponCodeGeneratorTest {

    private final CouponCodeGenerator generator = new CouponCodeGenerator();

    @Test
    @DisplayName("대문자와 숫자 10자리이고 헷갈리는 0, O, 1, I는 쓰지 않는다")
    void format() {
        for (int i = 0; i < 1_000; i++) {
            assertThat(generator.generate()).hasSize(CouponCodeGenerator.LENGTH).matches("[A-HJ-NP-Z2-9]{10}");
        }
    }

    @Test
    @DisplayName("1만 번 만들어도 겹치지 않는다")
    void unique() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            codes.add(generator.generate());
        }
        assertThat(codes).hasSize(10_000);
    }

    @Test
    @DisplayName("입력 코드는 앞뒤 공백을 지우고 대문자로 바꾼다")
    void normalize() {
        assertThat(CouponCodeGenerator.normalize("  abcd2345xy ")).isEqualTo("ABCD2345XY");
        assertThat(CouponCodeGenerator.normalize(null)).isEmpty();
    }
}
