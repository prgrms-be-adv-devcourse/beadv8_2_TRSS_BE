package com.backend.global.outbox;

// Outbox 이벤트 발행 상태
public enum OutboxEventStatus {
    PENDING,    // 저장됨, 아직 발행 전
    PUBLISHED,  // 릴레이가 발행함
    FAILED      // 재시도 한도를 넘겨 더 이상 발행하지 않음
}
