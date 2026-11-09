package com.backend.boundedContext.review.domain;

import com.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@RequiredArgsConstructor
@Accessors(fluent = true)
public enum ReviewErrorCode implements ErrorCode {
    // 없는 항목·다른 회원의 주문·구매 확정 전·취소·반품을 구분하지 않는다 (다른 회원 주문의 존재를 드러내지 않음)
    ORDER_ITEM_NOT_REVIEWABLE(404, "리뷰를 작성할 수 있는 주문 항목이 아닙니다."),
    REVIEW_ALREADY_EXISTS(409, "이미 리뷰를 작성한 주문 항목입니다."),
    // 없는 리뷰·삭제된 리뷰·다른 회원의 리뷰를 구분하지 않는다
    REVIEW_NOT_FOUND(404, "리뷰를 찾을 수 없습니다.");

    private final int status;
    private final String msg;
}
