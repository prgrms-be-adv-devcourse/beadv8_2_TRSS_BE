package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.app.dto.AdminInfluencerDetailDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MemberGetInfluencerDetailUseCase {
    private final InfluencerSupport influencerSupport;

    public AdminInfluencerDetailDto getInfluencerDetail(Long influencerId) {
        return influencerSupport.getInfluencerDetail(influencerSupport.getInfluencer(influencerId));
    }
}
