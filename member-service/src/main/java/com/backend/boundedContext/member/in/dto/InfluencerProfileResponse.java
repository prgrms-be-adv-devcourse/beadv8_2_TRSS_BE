package com.backend.boundedContext.member.in.dto;

import com.backend.boundedContext.member.domain.Influencer;

// 누구나 보는 인플루언서 정보 (검색 목록·프로필). 승인된 인플루언서만 보여 준다
public record InfluencerProfileResponse(
        Long influencerId,
        String activityName,
        String introduction
) {
    public static InfluencerProfileResponse from(Influencer influencer) {
        return new InfluencerProfileResponse(influencer.getId(), influencer.getActivityName(), influencer.getIntroduction());
    }
}
