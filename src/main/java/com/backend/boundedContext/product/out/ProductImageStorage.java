package com.backend.boundedContext.product.out;

/**
 * 상품 이미지 파일 저장소. 키(예: products/12/uuid.png)로 저장·삭제하고, 응답에 쓸 URL을 만든다.
 * 구현은 custom.product.image.storage로 고른다. 지금은 로컬 파일 시스템({@link LocalProductImageStorage})만 있다.
 */
public interface ProductImageStorage {

    /** 파일을 키 위치에 저장한다. 같은 키가 있으면 덮어쓴다. */
    void store(String key, byte[] content, String contentType);

    /** 키의 파일을 지운다. 없으면 아무것도 하지 않는다. */
    void delete(String key);

    /** 브라우저가 이미지를 받을 URL */
    String getUrl(String key);
}
