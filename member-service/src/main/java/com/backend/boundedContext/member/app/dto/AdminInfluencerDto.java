package com.backend.boundedContext.member.app.dto;

import com.backend.boundedContext.member.domain.Influencer;
import com.backend.boundedContext.member.domain.InfluencerStatus;

import java.time.LocalDateTime;

// 관리자 인플루언서 목록 항목
public record AdminInfluencerDto(
        Long influencerId,
        Long memberId,
        String email,
        String activityName,
        String snsUrl,
        InfluencerStatus status,
        LocalDateTime appliedAt
) {
    public static AdminInfluencerDto from(Influencer influencer) {
        return new AdminInfluencerDto(
                influencer.getId(),
                influencer.getMember().getId(),
                influencer.getMember().getEmail(),
                influencer.getActivityName(),
                influencer.getSnsUrl(),
                influencer.getStatus(),
                influencer.getAppliedAt()
        );
    }
}
