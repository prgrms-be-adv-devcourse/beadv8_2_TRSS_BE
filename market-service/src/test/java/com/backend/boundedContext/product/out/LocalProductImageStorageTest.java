package com.backend.boundedContext.product.out;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

class LocalProductImageStorageTest {

    @TempDir
    Path rootDir;

    @Test
    @DisplayName("키 경로에 파일을 저장하고(폴더는 새로 만든다), 지우면 파일이 없어진다")
    void storeAndDelete() {
        LocalProductImageStorage storage = new LocalProductImageStorage(rootDir.toString(), "http://localhost:8080/images");

        storage.store("products/12/a.png", new byte[]{1, 2, 3}, "image/png");
        assertThat(rootDir.resolve("products/12/a.png")).exists().hasBinaryContent(new byte[]{1, 2, 3});

        storage.delete("products/12/a.png");
        assertThat(rootDir.resolve("products/12/a.png")).doesNotExist();
        assertThatNoException().isThrownBy(() -> storage.delete("products/12/a.png"));   // 없는 파일이어도 된다
    }

    @Test
    @DisplayName("URL은 baseUrl + / + 키이고, baseUrl 끝의 / 는 하나만 남긴다")
    void url() {
        assertThat(new LocalProductImageStorage(rootDir.toString(), "http://localhost:8080/images").getUrl("products/1/a.png"))
                .isEqualTo("http://localhost:8080/images/products/1/a.png");
        assertThat(new LocalProductImageStorage(rootDir.toString(), "http://localhost:8080/images/").getUrl("products/1/a.png"))
                .isEqualTo("http://localhost:8080/images/products/1/a.png");
    }

    @Test
    @DisplayName("저장 폴더 밖을 가리키는 키는 거부한다")
    void rejectPathOutsideRoot() {
        LocalProductImageStorage storage = new LocalProductImageStorage(rootDir.toString(), "http://localhost:8080/images");

        assertThatThrownBy(() -> storage.store("../outside.png", new byte[]{1}, "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(rootDir.getParent().resolve("outside.png")).doesNotExist();
    }
}
