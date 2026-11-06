package com.backend.boundedContext.member.domain;

/**
 * 회원 탈퇴를 막는 이유. 탈퇴 조건 조회의 reasons와 탈퇴 거절(409 WITHDRAWAL_NOT_ALLOWED)의 data.reasons에 쓴다
 */
public enum WithdrawalBlockReason {
    ACTIVE_ORDERS,       // 진행 중인 주문이 있음
    ACTIVE_SALES,        // 판매자 상품이 들어간 진행 중인 주문이 있음 (판매자 상태와 무관)
    UNSETTLED_AMOUNT,    // 정산받지 않은 판매 금액이 있음
    BALANCE_REMAINING,   // 예치금이 남아 있음
    PENDING_TOPUPS,      // 결과가 정해지지 않은 충전 건(READY·PROCESSING)이 있음
    SELLER_ACTIVE        // 판매자 신청 심사 중·판매 중·철회 심사 중 (판매자 철회를 먼저 해야 함)
}
