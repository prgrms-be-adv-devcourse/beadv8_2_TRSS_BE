package com.backend.boundedContext.member.app.dto;

import java.util.Map;

import com.backend.shared.member.dto.SellerStatus;

// 관리자 판매자 목록의 탭별 건수 (상태 필터와 관계없이 전체 판매자 기준)
public record AdminSellerCountsDto(
        long all,
        long pending,
        long approved,
        long rejected,
        long withdrawRequested,
        long withdrawn,
        long sanctioned
) {
    // 상태별 건수에 없는 상태는 0으로 채운다
    public static AdminSellerCountsDto from(Map<SellerStatus, Long> counts) {
        return new AdminSellerCountsDto(
                counts.values().stream().mapToLong(Long::longValue).sum(),
                counts.getOrDefault(SellerStatus.PENDING, 0L),
                counts.getOrDefault(SellerStatus.APPROVED, 0L),
                counts.getOrDefault(SellerStatus.REJECTED, 0L),
                counts.getOrDefault(SellerStatus.WITHDRAW_REQUESTED, 0L),
                counts.getOrDefault(SellerStatus.WITHDRAWN, 0L),
                counts.getOrDefault(SellerStatus.SANCTIONED, 0L)
        );
    }
}
