package com.backend.boundedContext.payment.app;

/**
 * 대사 한 건의 처리 결과
 * - COMPLETED: 반영·확정 (취소 재요청 성공 포함)
 * - FAILED: 실패 기록·되돌림 (취소 재요청 실패 포함)
 * - PENDING: 아직 정할 수 없어 그대로 둠 (PG 진행 중, 사람 확인 대기)
 * - UNKNOWN: PG 조회 결과를 모름 (응답 없음 등). PG 장애일 수 있어 이번 회차는 이 배치까지만 처리함
 */
public enum TopupReconcileOutcome {
    COMPLETED, FAILED, PENDING, UNKNOWN
}
