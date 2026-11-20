package com.backend.shared.member.dto;

// 다른 서비스(market 쿠폰)에 주는 인플루언서 정보. 상태와 상관없이 주고, 승인 여부는 받는 쪽이 판단한다
public record InfluencerDto(
        Long influencerId,
        Long memberId,
        String activityName,
        InfluencerStatus status
) {

    public boolean approved() {
        return status == InfluencerStatus.APPROVED;
    }
}
