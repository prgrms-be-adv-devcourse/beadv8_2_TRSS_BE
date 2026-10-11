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
    CATEGORY_NOT_EDITABLE(400, "대분류는 수정할 수 없습니다.");

    private final int status;
    private final String msg;
}
