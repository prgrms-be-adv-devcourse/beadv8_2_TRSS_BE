package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.app.dto.AdminInfluencerDetailDto;
import com.backend.boundedContext.member.domain.Influencer;
import com.backend.boundedContext.member.domain.InfluencerStatusHistory;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.shared.member.event.InfluencerRevokedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 관리자 자격 해제 (APPROVED → REVOKED). 다시 신청할 수 없다.
 * 해제 이벤트를 커밋과 함께 outbox에 남기면, 쿠폰(market)이 받아 그 인플루언서의 협약을 종료하고 상품 쿠폰을 중지한다.
 */
@Component
@RequiredArgsConstructor
public class MemberRevokeInfluencerUseCase {
    private final InfluencerSupport influencerSupport;
    private final EventPublisher eventPublisher;

    public AdminInfluencerDetailDto revoke(Long influencerId, String reason) {
        Influencer influencer = influencerSupport.getInfluencer(influencerId);
        String strippedReason = reason.strip();
        InfluencerStatusHistory history = influencer.revoke(strippedReason);

        eventPublisher.publish(new InfluencerRevokedEvent(influencerId, influencer.getMember().getId(), strippedReason));
        return influencerSupport.saveHistory(influencer, history);
    }
}
