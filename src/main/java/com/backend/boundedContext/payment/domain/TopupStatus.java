package com.backend.boundedContext.payment.domain;

public enum TopupStatus {

    READY,                   // 충전 금액·주문번호 저장, 결제창 진행 전
    PROCESSING,              // 토스 승인 호출 중 또는 타임아웃으로 결과 확인 대기
    DONE,                    // 승인 완료, 예치금 반영
    CANCELED,                // 미사용 충전 토스 취소 완료
    FAILED_USER_CANCEL,      // 사용자가 결제창을 닫음
    FAILED_PG_DECLINED,      // 카드사 승인 거절
    FAILED_AMOUNT_MISMATCH,  // 저장한 금액과 승인 요청 금액이 다름
    FAILED_EXPIRED,          // READY 상태로 기준 시간 초과
    FAILED_SYSTEM_ERROR;     // 그 외 내부 오류

    public boolean isFailure() {
        return name().startsWith("FAILED_");
    }
}
