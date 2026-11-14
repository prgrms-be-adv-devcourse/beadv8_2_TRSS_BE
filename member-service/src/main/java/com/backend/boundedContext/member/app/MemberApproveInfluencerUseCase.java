package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.app.dto.AdminInfluencerDetailDto;
import com.backend.boundedContext.member.domain.Influencer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MemberApproveInfluencerUseCase {
    private final InfluencerSupport influencerSupport;

    public AdminInfluencerDetailDto approve(Long influencerId) {
        Influencer influencer = influencerSupport.getInfluencer(influencerId);
        return influencerSupport.saveHistory(influencer, influencer.approve());
    }
}
