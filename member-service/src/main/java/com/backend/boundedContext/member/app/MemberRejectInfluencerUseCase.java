package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.app.dto.AdminInfluencerDetailDto;
import com.backend.boundedContext.member.domain.Influencer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 관리자 반려 (PENDING → REJECTED). 사유는 이력에 남고 신청자에게 보인다. 반려된 회원은 다시 신청할 수 있다
@Component
@RequiredArgsConstructor
public class MemberRejectInfluencerUseCase {
    private final InfluencerSupport influencerSupport;

    public AdminInfluencerDetailDto reject(Long influencerId, String reason) {
        Influencer influencer = influencerSupport.getInfluencer(influencerId);
        return influencerSupport.saveHistory(influencer, influencer.reject(reason.strip()));
    }
}
