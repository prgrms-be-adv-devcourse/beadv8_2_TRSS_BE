package com.backend.boundedContext.product.domain;

/** 상품 이미지 유형 (product_image.image_type) */
public enum ProductImageType {
    THUMBNAIL,   // 대표 이미지. 상품마다 1개
    DETAIL       // 상세 이미지. 최대 10개, sort_order 순서로 보여 준다
}
