package com.backend.boundedContext.payment.domain;

public enum TopupStatus {

    READY,                      // 충전 금액·주문번호 저장, 결제창 진행 전
    PROCESSING,                 // 토스 승인 호출 중 또는 타임아웃으로 결과 확인 대기
    DONE,                       // 승인 완료, 예치금 반영 (일부만 취소된 충전 건도 DONE)
    CANCELING,                  // PG 취소 호출 중 또는 결과 확인 대기
    CANCELED,                   // 충전 금액 전부가 PG에서 취소됨
    METHOD_CANCELING,           // PG는 승인했지만 허용하지 않은 결제 수단이라 예치금에 반영하지 않고 PG 전액 취소 중
    FAILED_USER_CANCEL,         // 사용자가 결제창을 닫음
    FAILED_PG_DECLINED,         // 카드사 승인 거절
    FAILED_AMOUNT_MISMATCH,     // 저장한 금액과 승인 요청 금액이 다름
    FAILED_EXPIRED,             // READY 상태로 기준 시간 초과
    FAILED_SYSTEM_ERROR,        // 그 외 내부 오류
    FAILED_METHOD_NOT_ALLOWED;  // 허용하지 않은 결제 수단이라 PG에서 전액 취소함

    public boolean isFailure() {
        return name().startsWith("FAILED_");
    }
}
