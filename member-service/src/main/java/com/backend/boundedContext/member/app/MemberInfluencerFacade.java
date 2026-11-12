package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.in.dto.InfluencerApplyRequest;
import com.backend.boundedContext.member.in.dto.InfluencerProfileResponse;
import com.backend.boundedContext.member.in.dto.MyInfluencerResponse;
import com.backend.global.page.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 컨텍스트의 인플루언서 기능. 판매자 기능은 {@link MemberSellerFacade}
 */
@Service
@RequiredArgsConstructor
public class MemberInfluencerFacade {

    private final MemberApplyInfluencerUseCase memberApplyInfluencerUseCase;
    private final MemberGetInfluencerUseCase memberGetInfluencerUseCase;

    @Transactional
    public MyInfluencerResponse applyInfluencer(Long memberId, InfluencerApplyRequest request) {
        return memberApplyInfluencerUseCase.apply(memberId, request);
    }

    @Transactional(readOnly = true)
    public MyInfluencerResponse getMyInfluencer(Long memberId) {
        return memberGetInfluencerUseCase.getMyInfluencer(memberId);
    }

    @Transactional(readOnly = true)
    public PageResponse<InfluencerProfileResponse> searchInfluencers(Long memberId, String keyword, Pageable pageable) {
        return memberGetInfluencerUseCase.search(memberId, keyword, pageable);
    }

    @Transactional(readOnly = true)
    public InfluencerProfileResponse getInfluencerProfile(Long influencerId) {
        return memberGetInfluencerUseCase.getProfile(influencerId);
    }
}
