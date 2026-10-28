package com.backend.boundedContext.product.domain;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 상품 컨텍스트 정책 값
 */
@Component
@Getter
public class ProductPolicy {
    // 상품 하나에 올릴 수 있는 상세 이미지 최대 개수 (대표 이미지는 별도로 1개)
    private final int maxDetailImages;

    public ProductPolicy(
            @Value("${custom.product.image.maxDetailImages}") int maxDetailImages
    ) {
        this.maxDetailImages = maxDetailImages;
    }
}
