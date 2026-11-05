package com.backend.boundedContext.product.app.dto;

/**
 * 올린 이미지 파일의 내용. 형식은 파일명·Content-Type이 아니라 내용으로 판단한다 ({@code ProductImageFormat}).
 * 컨트롤러가 MultipartFile을 이 형태로 바꿔 넘긴다.
 */
public record ProductImageFile(
        byte[] content
) {
}
