package com.backend.boundedContext.recommend.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 임베딩할 글의 SHA-256. 같은 글이면 다시 임베딩하지 않으려고 저장해 둔다 */
public final class EmbeddingSource {

    private EmbeddingSource() {
    }

    public static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // 모든 JVM이 SHA-256을 지원한다
        }
    }
}
