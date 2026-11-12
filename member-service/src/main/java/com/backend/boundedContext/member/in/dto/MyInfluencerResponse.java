package com.backend.boundedContext.member.in.dto;

import com.backend.boundedContext.member.domain.Influencer;
import com.backend.boundedContext.member.domain.InfluencerStatus;
import com.backend.boundedContext.member.domain.InfluencerStatusHistory;

import java.time.LocalDateTime;

public record MyInfluencerResponse(
        Long influencerId,
        InfluencerStatus status,
        String activityName,
        String snsUrl,
        String introduction,
        LocalDateTime appliedAt,
        LocalDateTime decidedAt,    // 승인일·반려일. 심사 중이면 null
        String reason               // 반려 사유. 그 밖의 상태면 null
) {
    public static MyInfluencerResponse from(Influencer influencer, InfluencerStatusHistory latest) {
        return new MyInfluencerResponse(
                influencer.getId(),
                influencer.getStatus(),
                influencer.getActivityName(),
                influencer.getSnsUrl(),
                influencer.getIntroduction(),
                influencer.getAppliedAt(),
                influencer.getDecidedAt(),
                latest == null ? null : latest.getReason()
        );
    }
}
