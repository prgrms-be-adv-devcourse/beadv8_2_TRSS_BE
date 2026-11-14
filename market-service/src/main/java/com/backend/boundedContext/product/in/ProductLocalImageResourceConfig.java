package com.backend.boundedContext.product.in;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/**
 * 로컬 저장소를 쓸 때 저장 폴더의 파일을 /images/**로 내려 준다 (비로그인 허용은 SecurityConfig).
 * 켜지는 조건과 폴더는 {@code LocalProductImageStorage}와 같다.
 * 컨트롤러 슬라이스 테스트(@WebMvcTest)에서도 뜰 수 있게 저장소 빈 대신 설정값을 직접 읽는다.
 */
@Configuration
@ConditionalOnProperty(name = "custom.product.image.storage", havingValue = "local", matchIfMissing = true)
public class ProductLocalImageResourceConfig implements WebMvcConfigurer {
    private final String location;

    public ProductLocalImageResourceConfig(@Value("${custom.product.image.localDir}") String localDir) {
        String uri = Path.of(localDir).toAbsolutePath().normalize().toUri().toString();
        this.location = uri.endsWith("/") ? uri : uri + "/";   // 폴더가 아직 없어도 폴더로 본다
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/images/**").addResourceLocations(location);
    }
}
