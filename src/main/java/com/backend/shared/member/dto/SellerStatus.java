package com.backend.shared.member.dto;

public enum SellerStatus {
    PENDING,
    APPROVED,
    REJECTED,
    WITHDRAW_REQUESTED,  // 본인 철회 요청, 관리자 승인 대기
    WITHDRAWN,           // 본인 철회 완료 (재신청 가능)
    SANCTIONED           // 관리자 제재 (재신청 불가)
}
