package com.backend.boundedContext.member.domain;

/**
 * 관리자 회원 화면의 회원 유형.
 * 회원 역할(USER·ADMIN)에는 판매자가 없으므로 판매자 상태가 판매 중(APPROVED)인 회원을 판매자로 봄
 */
public enum MemberType {
    USER,    // 일반 회원: 역할 USER이고 판매 중인 판매자가 아님 (신청 전·심사 중·반려·철회 심사 중·철회·제재 포함)
    SELLER,  // 판매자: 역할 USER이고 판매자 상태가 APPROVED
    ADMIN    // 관리자: 역할 ADMIN
}
