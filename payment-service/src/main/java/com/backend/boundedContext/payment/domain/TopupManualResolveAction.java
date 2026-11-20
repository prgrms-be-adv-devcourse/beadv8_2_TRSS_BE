package com.backend.boundedContext.payment.domain;

// 수동 확인 건을 관리자가 정리하는 방법. 상태마다 고를 수 있는 것이 다름
public enum TopupManualResolveAction {

    CONFIRM_CANCEL(TopupStatus.CANCELING),               // PG에서 이번 취소가 처리된 것을 확인해 취소 확정
    RESTORE(TopupStatus.CANCELING),                      // PG에서 이번 취소가 처리되지 않은 것을 확인해 되돌림 (잔액·남은 금액 복원)
    CONFIRM_METHOD_CANCEL(TopupStatus.METHOD_CANCELING), // PG에서 전액 취소된 것을 확인해 확정 (상점관리자에서 직접 취소한 뒤 등)
    RETRY_METHOD_CANCEL(TopupStatus.METHOD_CANCELING);   // PG 전액 취소를 한 번 더 요청

    private final TopupStatus target;

    TopupManualResolveAction(TopupStatus target) {
        this.target = target;
    }

    public boolean appliesTo(TopupStatus status) {
        return target == status;
    }
}
