package com.backend.shared.member.dto;

// 판매자는 역할이 아니라 판매자 상태(SellerStatus.APPROVED)로 판단
// LOGISTICS: 물류 담당자(출고·송장 등록). 구매·판매·운영 기능은 쓸 수 없다
public enum MemberRole {
    USER,
    ADMIN,
    LOGISTICS
}
