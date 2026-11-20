package com.backend.boundedContext.product.domain;

import com.backend.global.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.backend.boundedContext.product.ProductImageFixtures.GIF_BYTES;
import static com.backend.boundedContext.product.ProductImageFixtures.JPEG_BYTES;
import static com.backend.boundedContext.product.ProductImageFixtures.PNG_BYTES;
import static com.backend.boundedContext.product.ProductImageFixtures.WEBP_BYTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductImageFormatTest {

    @Test
    @DisplayName("파일 앞부분으로 jpg·png·webp를 알아보고, 확장자와 Content-Type을 준다")
    void detect() {
        assertThat(ProductImageFormat.detect(JPEG_BYTES)).satisfies(format -> {
            assertThat(format).isEqualTo(ProductImageFormat.JPEG);
            assertThat(format.getExtension()).isEqualTo("jpg");
            assertThat(format.getContentType()).isEqualTo("image/jpeg");
        });
        assertThat(ProductImageFormat.detect(PNG_BYTES)).isEqualTo(ProductImageFormat.PNG);
        assertThat(ProductImageFormat.detect(WEBP_BYTES)).isEqualTo(ProductImageFormat.WEBP);
    }

    @Test
    @DisplayName("다른 형식이거나 비어 있거나 너무 짧으면 IMAGE_TYPE_NOT_ALLOWED")
    void detectNotAllowed() {
        for (byte[] content : new byte[][]{GIF_BYTES, new byte[0], {(byte) 0xFF, (byte) 0xD8}, "hello".getBytes()}) {
            assertThatThrownBy(() -> ProductImageFormat.detect(content))
                    .isInstanceOf(DomainException.class)
                    .extracting("resultCode").isEqualTo("400-IMAGE_TYPE_NOT_ALLOWED");
        }
    }
}
