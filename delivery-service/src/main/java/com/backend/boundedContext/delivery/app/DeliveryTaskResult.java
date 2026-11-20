package com.backend.boundedContext.delivery.app;

/**
 * 배송 스케줄러가 한 건을 처리한 결과
 */
public enum DeliveryTaskResult {
    // 끝냄 (배송 완료 처리, 웹훅 등록 성공)
    DONE,
    // 조회했지만 아직 배송 중 (택배사에 없는 송장 포함)
    IN_PROGRESS,
    // 처리할 필요가 없어짐 (배송 상태가 바뀜, 조회 중 송장 수정)
    SKIPPED,
    // 실패. 대상 값이 그대로 남아 다음 회차에 다시 한다
    FAILED
}
