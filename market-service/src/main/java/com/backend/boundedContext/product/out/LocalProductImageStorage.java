package com.backend.boundedContext.product.out;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 로컬 파일 시스템 저장소 (로컬 개발·테스트용). 파일은 localDir 아래 키 경로에 두고,
 * 앱이 /images/**로 내려 준다({@code ProductLocalImageResourceConfig}).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "custom.product.image.storage", havingValue = "local", matchIfMissing = true)
public class LocalProductImageStorage implements ProductImageStorage {
    private final Path rootDir;
    private final String baseUrl;

    public LocalProductImageStorage(@Value("${custom.product.image.localDir}") String localDir,
                                    @Value("${custom.product.image.baseUrl}") String baseUrl) {
        this.rootDir = Path.of(localDir).toAbsolutePath().normalize();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public Path getRootDir() {
        return rootDir;
    }

    @Override
    public void store(String key, byte[] content, String contentType) {
        Path path = resolve(key);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("상품 이미지를 저장하지 못했습니다. key=" + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            // 지우지 못한 파일은 화면에 보이지 않을 뿐이라 요청을 실패시키지 않는다
            log.warn("상품 이미지를 지우지 못했습니다. key={}", key, e);
        }
    }

    @Override
    public String getUrl(String key) {
        return baseUrl + "/" + key;
    }

    /** 키를 파일 경로로 바꾼다. ../ 등으로 저장 폴더 밖을 가리키면 거부한다. */
    private Path resolve(String key) {
        Path path = rootDir.resolve(key).normalize();
        if (!path.startsWith(rootDir)) {
            throw new IllegalArgumentException("저장 폴더 밖의 경로입니다. key=" + key);
        }
        return path;
    }
}
