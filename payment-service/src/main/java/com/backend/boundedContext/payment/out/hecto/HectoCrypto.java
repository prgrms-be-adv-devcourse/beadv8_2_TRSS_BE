package com.backend.boundedContext.payment.out.hecto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 헥토 요청·응답의 금액 암호화와 위변조 확인 해시
 * - 금액: AES-256/ECB/PKCS5Padding 암호화 후 Base64 (헥토 규격)
 * - 해시(pktHash): 정해진 순서로 이어 붙인 값 + 해시 키를 SHA-256 Hex로
 * 키가 비어 있어도 앱은 뜨고, 헥토를 호출할 때만 실패함 (토스 시크릿 키와 같게)
 */
@Component
public class HectoCrypto {

    private static final String AES = "AES";
    private static final String AES_TRANSFORMATION = "AES/ECB/PKCS5Padding";

    private final String aesKey;
    private final String hashKey;

    public HectoCrypto(
            @Value("${custom.hecto.aesKey}") String aesKey,
            @Value("${custom.hecto.hashKey}") String hashKey
    ) {
        this.aesKey = aesKey;
        this.hashKey = hashKey;
    }

    public String encryptAmount(long amount) {
        byte[] encrypted = aes(Cipher.ENCRYPT_MODE, String.valueOf(amount).getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encrypted);
    }

    public long decryptAmount(String encrypted) {
        byte[] decrypted = aes(Cipher.DECRYPT_MODE, Base64.getDecoder().decode(encrypted));
        return Long.parseLong(new String(decrypted, StandardCharsets.UTF_8));
    }

    // 값들을 순서대로 이어 붙이고 마지막에 해시 키를 붙여 SHA-256 Hex. 순서는 API마다 헥토 문서에 정해져 있음
    public String hash(String... values) {
        StringBuilder plain = new StringBuilder();

        for (String value : values) plain.append(value);
        plain.append(hashKey);

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(plain.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    /**
     * 암호화·복호화 공통 처리. mode가 Cipher.ENCRYPT_MODE면 암호화, Cipher.DECRYPT_MODE면 복호화
     * - 키: 설정한 암호화 키 문자열(32자)을 그대로 바이트로 써서 AES-256 키로 만듦
     * - ECB: 블록마다 따로 암호화하는 방식이라 IV(초기 벡터)가 없음 (헥토 규격)
     * - PKCS5Padding: 16바이트 블록에 모자란 만큼 채움. 금액은 짧아서 항상 한 블록(Base64 24자)
     * 키가 비었거나 길이가 맞지 않으면(IllegalArgumentException 포함) 설정 오류로 알림
     */
    private byte[] aes(int mode, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
            cipher.init(mode, new SecretKeySpec(aesKey.getBytes(StandardCharsets.UTF_8), AES));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("헥토 금액 암호화·복호화에 실패했습니다. 암호화 키 설정을 확인하세요.", e);
        }
    }
}
