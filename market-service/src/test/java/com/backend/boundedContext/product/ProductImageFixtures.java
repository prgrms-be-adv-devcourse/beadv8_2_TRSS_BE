package com.backend.boundedContext.product;

import com.backend.boundedContext.product.app.dto.ProductImageFile;

import java.util.Arrays;

/** 테스트용 이미지 파일. 형식은 파일 앞부분(시그니처)으로 판단하므로 시그니처 뒤는 아무 값이나 둔다. */
public final class ProductImageFixtures {

    public static final byte[] PNG_BYTES = withTail((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G', (byte) '\r', (byte) '\n', (byte) 0x1A, (byte) '\n');
    public static final byte[] JPEG_BYTES = withTail((byte) 0xFF, (byte) 0xD8, (byte) 0xFF);
    public static final byte[] WEBP_BYTES = withTail((byte) 'R', (byte) 'I', (byte) 'F', (byte) 'F', (byte) 0, (byte) 0, (byte) 0, (byte) 0,
            (byte) 'W', (byte) 'E', (byte) 'B', (byte) 'P');
    public static final byte[] GIF_BYTES = withTail((byte) 'G', (byte) 'I', (byte) 'F', (byte) '8', (byte) '9', (byte) 'a');

    public static final ProductImageFile THUMBNAIL = new ProductImageFile(PNG_BYTES);

    private ProductImageFixtures() {
    }

    public static ProductImageFile png() {
        return new ProductImageFile(PNG_BYTES);
    }

    public static ProductImageFile jpeg() {
        return new ProductImageFile(JPEG_BYTES);
    }

    public static ProductImageFile gif() {
        return new ProductImageFile(GIF_BYTES);
    }

    private static byte[] withTail(byte... signature) {
        byte[] content = Arrays.copyOf(signature, signature.length + 4);
        Arrays.fill(content, signature.length, content.length, (byte) 7);
        return content;
    }
}
