package com.backend.boundedContext.member.domain;

public enum InfluencerStatus {
    PENDING,
    APPROVED,
    REJECTED, // 관리자 반려. 다시 신청할 수 있다
    REVOKED // 관리자 자격 해제. 다시 신청할 수 없다
}
