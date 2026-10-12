package com.backend.shared.member.dto;

public enum SellerStatus {
    PENDING,
    APPROVED,
    REJECTED,
    WITHDRAW_REQUESTED,
    WITHDRAWN_SELF,      // 본인 철회 완료
    WITHDRAWN_SANCTION   // 관리자 제재
}
