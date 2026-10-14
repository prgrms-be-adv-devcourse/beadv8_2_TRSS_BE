package com.backend.boundedContext.product.in.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 상품 수정 요청. multipart의 request 파트(application/json)로 받는다. 보낸 값만 바뀌고, 재고는 바꿀 수 없다.
 *
 * @param name           상품명
 * @param price          가격 (원)
 * @param categoryId     소분류 ID (사용 중인 소분류만 가능)
 * @param description    상품 설명
 * @param detailImageIds 남길 기존 상세 이미지 ID (원하는 순서대로). 빠진 이미지는 삭제된다. 동작은 이미지 기능에서 구현
 */
public record ProductModifyRequest(
        @Size(max = 100) String name,
        @Positive Long price,
        Long categoryId,
        String description,
        @Size(max = 10) List<Long> detailImageIds
) {
    @AssertTrue(message = "상품명은 공백일 수 없습니다.")
    public boolean isNameNotBlank() {
        return name == null || !name.isBlank();
    }

    @AssertTrue(message = "상품 설명은 공백일 수 없습니다.")
    public boolean isDescriptionNotBlank() {
        return description == null || !description.isBlank();
    }
}
