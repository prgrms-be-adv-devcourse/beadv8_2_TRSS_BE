package com.backend.boundedContext.product.out;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 실제 S3 없이 S3 클라이언트를 mock으로 두고 요청 내용만 확인한다 */
class S3ProductImageStorageTest {

    private static final String BUCKET = "palette-images";
    private static final String BASE_URL = "https://palette-images.s3.ap-northeast-2.amazonaws.com";

    private final S3Client s3Client = mock(S3Client.class);
    private final S3ProductImageStorage storage = new S3ProductImageStorage(s3Client, BUCKET, BASE_URL);

    @Test
    @DisplayName("저장: 버킷의 키 위치에 Content-Type과 캐시 설정을 넣어 올린다")
    void store() throws Exception {
        storage.store("products/1/a.png", new byte[]{1, 2, 3}, "image/png");

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.captor();
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.captor();
        verify(s3Client).putObject(request.capture(), body.capture());
        assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(request.getValue().key()).isEqualTo("products/1/a.png");
        assertThat(request.getValue().contentType()).isEqualTo("image/png");
        assertThat(request.getValue().cacheControl()).isEqualTo(S3ProductImageStorage.CACHE_CONTROL);
        assertThat(body.getValue().contentStreamProvider().newStream().readAllBytes()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("저장: S3 오류는 예외로 올려 요청을 실패시킨다 (트랜잭션 롤백)")
    void storeFails() {
        given(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .willThrow(SdkClientException.create("연결 실패"));

        assertThatThrownBy(() -> storage.store("products/1/a.png", new byte[]{1}, "image/png"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("products/1/a.png");
    }

    @Test
    @DisplayName("삭제: 버킷의 키를 지우고, S3 오류는 요청을 실패시키지 않는다")
    void delete() {
        storage.delete("products/1/a.png");

        ArgumentCaptor<DeleteObjectRequest> request = ArgumentCaptor.captor();
        verify(s3Client).deleteObject(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(request.getValue().key()).isEqualTo("products/1/a.png");

        given(s3Client.deleteObject(any(DeleteObjectRequest.class))).willThrow(SdkClientException.create("연결 실패"));
        assertThatCode(() -> storage.delete("products/1/b.png")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("URL: S3 주소 + / + 키이고, baseUrl 끝의 /와 앞뒤 공백은 무시한다")
    void getUrl() {
        assertThat(storage.getUrl("products/1/a.png")).isEqualTo(BASE_URL + "/products/1/a.png");
        assertThat(new S3ProductImageStorage(s3Client, BUCKET, " " + BASE_URL + "/ ").getUrl("products/1/a.png"))
                .isEqualTo(BASE_URL + "/products/1/a.png");
    }

    @Test
    @DisplayName("설정: 버킷이나 URL이 비어 있으면 만들 수 없다 (앱 시작 실패)")
    void blankConfig() {
        assertThatThrownBy(() -> new S3ProductImageStorage(s3Client, "", BASE_URL))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new S3ProductImageStorage(s3Client, BUCKET, " "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("저장소 선택: storage=s3면 S3 저장소만, local이거나 값이 없으면 로컬 저장소만 켜진다")
    void conditional() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(ProductS3ClientConfig.class, S3ProductImageStorage.class, LocalProductImageStorage.class)
                .withPropertyValues(
                        "custom.product.image.localDir=build/test-uploads",
                        "custom.product.image.baseUrl=http://localhost:8080/images",
                        "custom.product.image.s3.region=ap-northeast-2");

        runner.withPropertyValues("custom.product.image.storage=s3",
                        "custom.product.image.s3.bucket=" + BUCKET, "custom.product.image.s3.baseUrl=" + BASE_URL)
                .run(context -> assertThat(context.getBean(ProductImageStorage.class)).isInstanceOf(S3ProductImageStorage.class));
        runner.withPropertyValues("custom.product.image.storage=s3",
                        "custom.product.image.s3.bucket=", "custom.product.image.s3.baseUrl=")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("custom.product.image.storage=local")
                .run(context -> assertThat(context.getBean(ProductImageStorage.class)).isInstanceOf(LocalProductImageStorage.class));
        runner.run(context -> {
            assertThat(context.getBean(ProductImageStorage.class)).isInstanceOf(LocalProductImageStorage.class);
            assertThat(context).doesNotHaveBean(S3Client.class);
        });
    }
}
