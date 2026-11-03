package com.backend.boundedContext.product.out;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * S3 저장소를 쓸 때만 S3 클라이언트를 만든다 (켜지는 조건은 {@link S3ProductImageStorage}와 같다).
 * 인증은 SDK 기본 방식을 따른다. 운영(EC2)에서는 인스턴스에 붙인 IAM 역할을 쓰므로 액세스 키를 넣지 않는다.
 */
@Configuration
@ConditionalOnProperty(name = "custom.product.image.storage", havingValue = "s3")
public class ProductS3ClientConfig {

    @Bean(destroyMethod = "close")
    public S3Client productImageS3Client(@Value("${custom.product.image.s3.region}") String region) {
        return S3Client.builder()
                .region(Region.of(region))
                .build();
    }
}
