package com.backend.boundedContext.faq.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** FAQ 카테고리. label은 챗봇 답변의 출처 표시·관리자 화면에 씀 */
@Getter
@RequiredArgsConstructor
public enum FaqCategory {

    MEMBER("회원"),
    TOPUP("충전"),
    ORDER("주문·결제"),
    DELIVERY("배송"),
    RETURN("반품·환불"),
    SELLER("판매·정산");

    private final String label;
}
