package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.domain.Influencer;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.in.dto.InternalInfluencerResponse;
import com.backend.boundedContext.member.out.InfluencerRepository;
import com.backend.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

// 다른 서비스(market 쿠폰)용 인플루언서 조회. 승인 여부는 받는 쪽이 status로 판단하게 상태와 상관없이 돌려준다
@Component
@RequiredArgsConstructor
public class MemberGetInternalInfluencerUseCase {
    private final InfluencerRepository influencerRepository;

    public InternalInfluencerResponse getInfluencer(Long influencerId) {
        return influencerRepository.findById(influencerId)
                .map(InternalInfluencerResponse::from)
                .orElseThrow(() -> new DomainException(MemberErrorCode.INFLUENCER_NOT_FOUND));
    }

    // 없는 ID는 결과에서 빠진다
    public List<InternalInfluencerResponse> getInfluencers(Collection<Long> influencerIds) {
        return influencerRepository.findAllById(influencerIds).stream()
                .map(InternalInfluencerResponse::from)
                .toList();
    }

    // 인플루언서 기능(협약 수락 등) 권한 확인. 신청한 적이 없거나 승인 상태가 아니면 403
    public InternalInfluencerResponse getApprovedInfluencerByMemberId(Long memberId) {
        return influencerRepository.findByMemberId(memberId)
                .filter(Influencer::isApproved)
                .map(InternalInfluencerResponse::from)
                .orElseThrow(() -> new DomainException(MemberErrorCode.INFLUENCER_NOT_APPROVED));
    }
}
