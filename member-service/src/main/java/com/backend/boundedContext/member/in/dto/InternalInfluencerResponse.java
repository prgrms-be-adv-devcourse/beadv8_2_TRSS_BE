package com.backend.boundedContext.member.in.dto;

import com.backend.boundedContext.member.domain.Influencer;
import com.backend.boundedContext.member.domain.InfluencerStatus;

// 다른 서비스(market 쿠폰)에 주는 인플루언서 정보. 상태와 상관없이 돌려주고, 승인 여부는 받는 쪽이 status로 판단한다
public record InternalInfluencerResponse(
        Long influencerId,
        Long memberId,
        String activityName,
        InfluencerStatus status
) {
    public static InternalInfluencerResponse from(Influencer influencer) {
        return new InternalInfluencerResponse(influencer.getId(), influencer.getMember().getId(), influencer.getActivityName(), influencer.getStatus());
    }
}
