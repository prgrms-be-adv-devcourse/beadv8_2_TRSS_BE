package com.backend.boundedContext.member.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountNumberConverterTest {
    private final AccountNumberConverter converter = new AccountNumberConverter("test-key", "5c0744940b5c369b");

    @Test
    @DisplayName("암호화한 값을 복호화하면 원문이 나온다")
    void encryptThenDecrypt() {
        String stored = converter.convertToDatabaseColumn("12345601123456");

        assertThat(converter.convertToEntityAttribute(stored))
                .isEqualTo("12345601123456");
    }

    @Test
    @DisplayName("DB에 저장되는 값에는 원문이 드러나지 않는다")
    void storedValueHidesPlainText() {
        String stored = converter.convertToDatabaseColumn("12345601123456");

        assertThat(stored).doesNotContain("12345601123456");
    }

    @Test
    @DisplayName("같은 계좌번호도 암호화할 때마다 다른 값이 된다 (랜덤 IV)")
    void sameInputDifferentOutput() {
        String first = converter.convertToDatabaseColumn("12345601123456");
        String second = converter.convertToDatabaseColumn("12345601123456");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("암호문은 컬럼 길이(255) 안에 들어간다")
    void fitsInColumn() {
        String stored = converter.convertToDatabaseColumn("1".repeat(30));

        assertThat(stored.length()).isLessThanOrEqualTo(255);
    }

    @Test
    @DisplayName("다른 키로는 복호화할 수 없다")
    void otherKeyCannotDecrypt() {
        String stored = converter.convertToDatabaseColumn("12345601123456");
        AccountNumberConverter otherKey = new AccountNumberConverter("other-key", "5c0744940b5c369b");

        assertThatThrownBy(() -> otherKey.convertToEntityAttribute(stored)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("null은 그대로 null")
    void nullStaysNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
