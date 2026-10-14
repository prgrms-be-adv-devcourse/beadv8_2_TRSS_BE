package com.backend.boundedContext.product.domain;

import com.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@RequiredArgsConstructor
@Accessors(fluent = true)
public enum ProductErrorCode implements ErrorCode {
    // 카테고리
    CATEGORY_NOT_FOUND(404, "카테고리를 찾을 수 없습니다."),
    CATEGORY_DUPLICATED(409, "같은 대분류 아래에 같은 이름의 카테고리가 있습니다."),
    CATEGORY_DEPTH_EXCEEDED(400, "소분류 아래에는 카테고리를 추가할 수 없습니다."),
    CATEGORY_NOT_EDITABLE(400, "대분류는 수정할 수 없습니다."),
    CATEGORY_NOT_SELECTABLE(400, "사용 중인 소분류만 선택할 수 있습니다."),

    // 상품·재고
    PRODUCT_NOT_FOUND(404, "상품을 찾을 수 없습니다."),
    PRODUCT_NOT_ON_SALE(409, "판매 중인 상품이 아닙니다."),
    PRODUCT_NOT_EDITABLE(409, "판매 중지되었거나 삭제된 상품은 수정할 수 없습니다."),
    OUT_OF_STOCK(400, "재고가 부족합니다."),
    STOCK_NEGATIVE(400, "재고는 0 미만이 될 수 없습니다."),
    THUMBNAIL_REQUIRED(400, "대표 이미지는 필수입니다."),
    PRODUCT_DELETED(409, "삭제된 상품입니다."),
    OPTION_DELETED(409, "삭제된 옵션입니다.");

    private final int status;
    private final String msg;
}
