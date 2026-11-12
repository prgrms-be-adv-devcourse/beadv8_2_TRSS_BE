package com.backend.boundedContext.member.app.dto;

import com.backend.boundedContext.member.domain.Influencer;
import com.backend.boundedContext.member.domain.InfluencerStatusHistory;
import com.backend.boundedContext.member.domain.Member;
import com.backend.shared.member.dto.InfluencerStatus;

import java.time.LocalDateTime;
import java.util.List;

// 관리자 인플루언서 상세
public record AdminInfluencerDetailDto(
        Long influencerId,
        Long memberId,
        String email,
        String name,
        InfluencerStatus status,
        String activityName,
        String snsUrl,
        String introduction,
        LocalDateTime appliedAt,
        LocalDateTime decidedAt,
        String reason,                  // 가장 최근 이력의 사유 (반려)
        List<History> histories         // 오래된 순
) {
    public record History(
            InfluencerStatus fromStatus,    // 처음 신청이면 null
            InfluencerStatus toStatus,
            String reason,
            LocalDateTime changedAt
    ) {
        static History from(InfluencerStatusHistory history) {
            return new History(history.getFromStatus(), history.getToStatus(), history.getReason(), history.getChangedAt());
        }
    }

    public static AdminInfluencerDetailDto of(Influencer influencer, List<InfluencerStatusHistory> histories) {
        Member member = influencer.getMember();
        return new AdminInfluencerDetailDto(
                influencer.getId(),
                member.getId(),
                member.getEmail(),
                member.getName(),
                influencer.getStatus(),
                influencer.getActivityName(),
                influencer.getSnsUrl(),
                influencer.getIntroduction(),
                influencer.getAppliedAt(),
                influencer.getDecidedAt(),
                histories.isEmpty() ? null : histories.getLast().getReason(),
                histories.stream().map(History::from).toList()
        );
    }
}
