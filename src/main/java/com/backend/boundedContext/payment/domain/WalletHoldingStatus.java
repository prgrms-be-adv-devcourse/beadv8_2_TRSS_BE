package com.backend.boundedContext.payment.domain;

public enum WalletHoldingStatus {
    HELD,      // 결제 성공으로 보관 중 (일부만 정산된 경우도 HELD)
    RELEASED,  // 반품 환불로 해제
    SETTLED    // 월 정산 완료 (정산 누적 금액 = 홀딩 금액)
}
