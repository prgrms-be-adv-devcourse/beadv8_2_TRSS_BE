package com.backend.boundedContext.member.app.dto;

import com.backend.global.page.PageResponse;

public record AdminInfluencerListDto(
        PageResponse<AdminInfluencerDto> influencers,
        AdminInfluencerCountsDto counts
) {
}
