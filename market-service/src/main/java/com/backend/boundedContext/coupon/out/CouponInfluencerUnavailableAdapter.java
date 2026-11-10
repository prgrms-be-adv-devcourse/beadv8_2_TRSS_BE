package com.backend.boundedContext.coupon.out;

import com.backend.boundedContext.coupon.domain.CouponErrorCode;
import com.backend.global.exception.DomainException;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * {@link CouponInfluencerPort} 임시 구현. member-service에 인플루언서 기능이 생기기 전까지 쓴다.
 *
 * <p>안전한 쪽으로 막는다: 어떤 회원도 인플루언서가 아니고, 어떤 인플루언서 ID도 없는 것으로 답한다.
 * 그래서 협약 요청은 404-INFLUENCER_NOT_FOUND, 인플루언서 API(/influencer/**)는 403-INFLUENCER_NOT_APPROVED가 된다.
 * 인플루언서 API가 생기면 이 클래스를 지우고 member-service를 부르는 구현체로 바꾼다.
 */
@Component
public class CouponInfluencerUnavailableAdapter implements CouponInfluencerPort {

    @Override
    public Optional<CouponInfluencer> findInfluencer(Long influencerId) {
        return Optional.empty();
    }

    @Override
    public Long getApprovedInfluencerIdByMemberId(Long memberId) {
        throw new DomainException(CouponErrorCode.INFLUENCER_NOT_APPROVED);
    }

    @Override
    public Map<Long, String> getActivityNames(Collection<Long> influencerIds) {
        return Map.of();
    }
}
