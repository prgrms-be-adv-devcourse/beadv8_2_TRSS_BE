package com.backend.boundedContext.member.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.codec.Hex;
import org.springframework.security.crypto.encrypt.AesGcmBytesEncryptor;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.stereotype.Component;

import static java.nio.charset.StandardCharsets.UTF_8;

@Component
@Converter
public class AccountNumberConverter implements AttributeConverter<String, String> {
    private final BytesEncryptor encryptor;

    public AccountNumberConverter(
            @Value("${custom.member.accountEncryption.password}") String password,
            @Value("${custom.member.accountEncryption.salt}") String salt
    ) {
        // AES-256-GCM, 매번 랜덤 IV. 키 생성(PBKDF2)이 무거워서 한 번만 만들어 재사용한다
        this.encryptor = AesGcmBytesEncryptor.withPassword(password, salt).build();
    }

    @Override
    public String convertToDatabaseColumn(String plainText) {
        return plainText == null ? null : new String(Hex.encode(encryptor.encrypt(plainText.getBytes(UTF_8))));
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData == null ? null : new String(encryptor.decrypt(Hex.decode(dbData)), UTF_8);
    }
}
