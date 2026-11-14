package com.backend.boundedContext.payment.app;

import java.time.LocalDateTime;

/**
 * 대사가 한 회차 안에서 다음 배치를 이어서 가져올 위치: 조회 순서(updatedAt, id)에서 마지막으로 가져온 건
 * 이 위치 다음부터 가져오므로, 결과를 아직 몰라 그대로 남은 건을 같은 회차에 다시 가져오지 않음
 */
public record TopupReconcileCursor(
        LocalDateTime updatedAt,
        Long topupId
) {
    // 첫 배치: 모든 충전 건보다 앞
    public static final TopupReconcileCursor START = new TopupReconcileCursor(LocalDateTime.of(2000, 1, 1, 0, 0), 0L);
}
