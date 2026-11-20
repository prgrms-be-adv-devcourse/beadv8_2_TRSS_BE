package com.backend.boundedContext.product.domain;

/** 상품 할인의 상태. 저장하지 않고 조회 시각으로 계산한다. */
public enum ProductDiscountStatus {
    /** 시작 전. 수정·삭제할 수 있다 */
    SCHEDULED,
    /** 진행 중. 지금 종료만 할 수 있다 */
    ACTIVE,
    /** 끝남. 읽기만 한다 (이력) */
    ENDED
}
