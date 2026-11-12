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
    STOCK_LIMIT_EXCEEDED(400, "재고는 1,000,000개를 넘을 수 없습니다."),
    THUMBNAIL_REQUIRED(400, "대표 이미지는 필수입니다."),
    IMAGE_TYPE_NOT_ALLOWED(400, "jpg, png, webp 이미지만 올릴 수 있습니다."),
    DETAIL_IMAGE_LIMIT_EXCEEDED(400, "상세 이미지는 최대 10개까지 올릴 수 있습니다."),
    DETAIL_IMAGE_NOT_FOUND(400, "이 상품의 상세 이미지가 아닙니다."),

    // 옵션
    OPTION_NAME_INVALID(400, "옵션 이름은 최대 3개, 각각 1~20자이며 서로 달라야 합니다."),
    OPTION_VALUE_INVALID(400, "옵션 값은 옵션 이름마다 하나씩, 각각 1~14자여야 합니다."),
    OPTION_DUPLICATED(409, "같은 조합의 옵션이 이미 있습니다."),
    OPTION_LIMIT_EXCEEDED(400, "옵션 조합은 최대 100개까지 만들 수 있습니다."),
    LAST_OPTION_NOT_DELETABLE(409, "마지막 남은 옵션은 삭제할 수 없습니다."),

    // 판매 중지 요청
    STOP_REQUEST_PENDING(409, "심사 중인 판매 중지 요청이 이미 있습니다."),
    STOP_REQUEST_NOT_PENDING(409, "심사 중인 요청만 처리할 수 있습니다."),
    STOP_REQUEST_NOT_FOUND(404, "판매 중지 요청을 찾을 수 없습니다."),
    PRODUCT_DELETED(409, "삭제된 상품입니다."),
    OPTION_DELETED(409, "삭제된 옵션입니다."),

    // 찜
    SELF_WISH_NOT_ALLOWED(400, "본인이 판매하는 상품은 찜할 수 없습니다.");

    private final int status;
    private final String msg;
}
