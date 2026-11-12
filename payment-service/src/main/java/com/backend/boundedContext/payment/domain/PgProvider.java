package com.backend.boundedContext.payment.domain;

/**
 * 충전에 쓰는 결제대행사(PG). 충전 건마다 어느 PG로 결제했는지 기록하고, 승인·조회·취소를 같은 PG로 보냄
 */
public enum PgProvider {
    TOSS  // 토스페이먼츠
}
