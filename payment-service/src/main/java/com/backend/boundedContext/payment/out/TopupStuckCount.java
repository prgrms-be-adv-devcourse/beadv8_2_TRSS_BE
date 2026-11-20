package com.backend.boundedContext.payment.out;

import com.backend.boundedContext.payment.domain.TopupStatus;

/**
 * 오래 남은 충전 건의 상태별 집계
 * - count: 기준 시각 이전부터 이 상태로 남은 건수
 * - minTopupId: 그중 가장 작은 충전 건 ID (가장 먼저 만든 건. 확인을 시작할 건)
 */
public record TopupStuckCount(
        TopupStatus status,
        long count,
        Long minTopupId
) {}
