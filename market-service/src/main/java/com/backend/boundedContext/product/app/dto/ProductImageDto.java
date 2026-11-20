package com.backend.boundedContext.product.app.dto;

/** 상품 이미지. 수정할 때 남길 이미지를 imageId로 고른다. */
public record ProductImageDto(
        Long imageId,
        String url
) {
}
