package com.backend.boundedContext.payment.domain;

// 반품 환불 상태. 실패는 사유별로 나눈다(결제 상태 FAILED_*와 같은 방식)
// FAILED_SYSTEM_ERROR만 재시도하고, 나머지 실패는 다시 해도 결과가 같아 사람이 확인한다
public enum PaymentRefundStatus {
    REQUESTED,                   // 환불 요청 기록됨, 아직 처리 전
    PICKUP_FEE_REQUIRED,         // 단순 변심: 확인은 끝났고 구매자가 반품 회수 택배비를 결제하면 환불
    COMPLETED,                   // 환불 완료
    FAILED_AMOUNT_MISMATCH,      // 이벤트 금액 ≠ 결제 금액
    FAILED_HOLDING_SETTLED,      // 홀딩이 일부라도 정산됨
    FAILED_PAYMENT_NOT_PAID,     // 주문에 결제 완료 건이 없음 (예: 이미 결제 취소)
    FAILED_PLATFORM_BALANCE,     // 플랫폼 지갑 잔액이 모자라 배송비를 돌려받지 못함
    FAILED_SYSTEM_ERROR;         // 잠금 대기 초과·DB 연결 끊김 같은 일시적 오류. 재시도한다

    public boolean isFailure() {
        return name().startsWith("FAILED_");
    }
}
