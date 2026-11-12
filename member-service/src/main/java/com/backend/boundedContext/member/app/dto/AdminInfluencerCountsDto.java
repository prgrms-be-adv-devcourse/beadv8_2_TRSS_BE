package com.backend.boundedContext.member.app.dto;

import com.backend.boundedContext.member.domain.InfluencerStatus;

import java.util.Map;

// 관리자 인플루언서 목록의 탭별 건수 (상태 필터와 관계없이 전체 기준)
public record AdminInfluencerCountsDto(
        long all,
        long pending,
        long approved,
        long rejected,
        long revoked
) {
    // 상태별 건수에 없는 상태는 0으로 채운다
    public static AdminInfluencerCountsDto from(Map<InfluencerStatus, Long> counts) {
        return new AdminInfluencerCountsDto(
                counts.values().stream().mapToLong(Long::longValue).sum(),
                counts.getOrDefault(InfluencerStatus.PENDING, 0L),
                counts.getOrDefault(InfluencerStatus.APPROVED, 0L),
                counts.getOrDefault(InfluencerStatus.REJECTED, 0L),
                counts.getOrDefault(InfluencerStatus.REVOKED, 0L)
        );
    }
}
