package com.backend.boundedContext.coupon.out;

import com.backend.market.client.MemberInfluencerApiClient;
import com.backend.shared.member.dto.InfluencerDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@link CouponInfluencerPort} 구현. 회원 서비스의 인플루언서 내부 API를 부른다.
 * 회원 서비스가 응답하지 못하면 502가 난다("인플루언서 아님"으로 바꿔 답하지 않는다)
 */
@Component
@RequiredArgsConstructor
public class CouponInfluencerMemberApiAdapter implements CouponInfluencerPort {

    private final MemberInfluencerApiClient memberInfluencerApiClient;

    @Override
    public Optional<CouponInfluencer> findInfluencer(Long influencerId) {
        return memberInfluencerApiClient.findInfluencer(influencerId)
                .map(influencer -> new CouponInfluencer(
                        influencer.influencerId(), influencer.memberId(), influencer.activityName(), influencer.approved()));
    }

    @Override
    public Long getApprovedInfluencerIdByMemberId(Long memberId) {
        return memberInfluencerApiClient.getApprovedInfluencerByMemberId(memberId).influencerId();
    }

    @Override
    public Map<Long, String> getActivityNames(Collection<Long> influencerIds) {
        if (influencerIds.isEmpty()) return Map.of();

        return memberInfluencerApiClient.getInfluencers(new HashSet<>(influencerIds)).stream()
                .collect(Collectors.toMap(InfluencerDto::influencerId, InfluencerDto::activityName, (first, second) -> first));
    }
}
