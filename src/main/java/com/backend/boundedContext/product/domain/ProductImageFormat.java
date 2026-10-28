package com.backend.boundedContext.product.domain;

import com.backend.global.exception.DomainException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * 올릴 수 있는 이미지 형식. 클라이언트가 보낸 Content-Type이나 파일명은 믿지 않고, 파일 앞부분(시그니처)으로 판단한다.
 */
@Getter
@RequiredArgsConstructor
public enum ProductImageFormat {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    WEBP("webp", "image/webp");

    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] RIFF = {'R', 'I', 'F', 'F'};
    private static final byte[] WEBP_MARK = {'W', 'E', 'B', 'P'};

    private final String extension;
    private final String contentType;

    /** 파일 내용으로 형식을 찾는다. 비어 있거나 jpg·png·webp가 아니면 400. */
    public static ProductImageFormat detect(byte[] content) {
        if (startsWith(content, 0, JPEG_SIGNATURE)) return JPEG;
        if (startsWith(content, 0, PNG_SIGNATURE)) return PNG;
        if (startsWith(content, 0, RIFF) && startsWith(content, 8, WEBP_MARK)) return WEBP;
        throw new DomainException(ProductErrorCode.IMAGE_TYPE_NOT_ALLOWED);
    }

    private static boolean startsWith(byte[] content, int offset, byte[] signature) {
        if (content == null || content.length < offset + signature.length) return false;
        return Arrays.equals(content, offset, offset + signature.length, signature, 0, signature.length);
    }
}
