package com.backend.boundedContext.payment.out.hecto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HectoCryptoTest {

    // 테스트용 키. 기대값은 openssl enc -aes-256-ecb -nosalt -base64, shasum -a 256 으로 따로 계산함
    private static final String AES_KEY = "0123456789abcdef0123456789abcdef";
    private static final String HASH_KEY = "TEST_HASH_KEY";

    private final HectoCrypto crypto = new HectoCrypto(AES_KEY, HASH_KEY);

    @ParameterizedTest(name = "{0}원 → {1}")
    @CsvSource({
            "1000, ugDv9Ga9e7Ogzxz1Qeu3Jg==",
            "20000, Tfe/21SpHXuRrVe8f0Aqjw==",
            "300, 0h5rN2gFzXeglWCnYTENfg=="
    })
    @DisplayName("금액을 AES-256/ECB/PKCS5Padding으로 암호화해 Base64로 만들고, 다시 풀면 원래 금액이다")
    void encryptAndDecryptAmount(long amount, String encrypted) {
        assertThat(crypto.encryptAmount(amount)).isEqualTo(encrypted);
        assertThat(crypto.decryptAmount(encrypted)).isEqualTo(amount);
    }

    @Test
    @DisplayName("값들을 순서대로 이어 붙이고 해시 키를 붙여 SHA-256 Hex로 만든다")
    void hash() {
        assertThat(crypto.hash("a", "b", "c")).isEqualTo("59e0362ff6f8e25d5ba73b1c4aac62af634c6a2493852a458a54f917ba5e4528");
    }

    @Test
    @DisplayName("암호화 키가 설정되지 않았으면 IllegalStateException")
    void encrypt_emptyKey() {
        HectoCrypto noKey = new HectoCrypto("", HASH_KEY);

        assertThatThrownBy(() -> noKey.encryptAmount(1000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("암호화 키");
    }
}
