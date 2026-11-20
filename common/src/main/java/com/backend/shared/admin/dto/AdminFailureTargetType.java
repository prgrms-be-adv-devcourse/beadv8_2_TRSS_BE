package com.backend.shared.admin.dto;

/** 실패 거래의 대상. 표의 "대상" 칸에 회원 또는 판매자로 보인다 */
public enum AdminFailureTargetType {
    /** 회원 (구매자, 충전한 회원). targetId = 회원 ID */
    MEMBER,
    /** 판매자. targetId = 판매자 ID */
    SELLER
}
