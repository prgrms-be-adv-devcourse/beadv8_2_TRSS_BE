package com.backend.boundedContext.product.out;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * S3 저장소 (배포 환경용). 파일은 버킷의 키 위치에 두고, 버킷의 products/*를 공개 읽기로 열어 S3 주소로 바로 내려 준다.
 * 버킷·URL 설정이 비어 있으면 잘못 배포된 것이므로 앱이 뜰 때 바로 실패한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "custom.product.image.storage", havingValue = "s3")
public class S3ProductImageStorage implements ProductImageStorage {
    /** 키에 UUID가 들어가 같은 키의 내용이 바뀌지 않으므로 브라우저가 오래 캐시해도 된다 */
    static final String CACHE_CONTROL = "public, max-age=31536000, immutable";

    private final S3Client s3Client;
    private final String bucket;
    private final String baseUrl;

    public S3ProductImageStorage(S3Client s3Client,
                                 @Value("${custom.product.image.s3.bucket}") String bucket,
                                 @Value("${custom.product.image.s3.baseUrl}") String baseUrl) {
        if (bucket.isBlank() || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "S3 저장소를 쓰려면 PRODUCT_IMAGE_S3_BUCKET과 PRODUCT_IMAGE_S3_BASE_URL을 설정해야 합니다.");
        }
        this.s3Client = s3Client;
        this.bucket = bucket.strip();
        String url = baseUrl.strip();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Override
    public void store(String key, byte[] content, String contentType) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .cacheControl(CACHE_CONTROL)
                .build();
        try {
            s3Client.putObject(request, RequestBody.fromBytes(content));
        } catch (SdkException e) {
            throw new IllegalStateException("상품 이미지를 저장하지 못했습니다. key=" + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (SdkException e) {
            // 지우지 못한 파일은 화면에 보이지 않을 뿐이라 요청을 실패시키지 않는다 (로컬 저장소와 같음)
            log.warn("상품 이미지를 지우지 못했습니다. key={}", key, e);
        }
    }

    @Override
    public String getUrl(String key) {
        return baseUrl + "/" + key;
    }
}
