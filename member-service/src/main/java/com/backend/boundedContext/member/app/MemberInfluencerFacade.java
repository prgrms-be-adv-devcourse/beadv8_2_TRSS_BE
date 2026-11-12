package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.app.dto.AdminInfluencerDetailDto;
import com.backend.boundedContext.member.app.dto.AdminInfluencerListDto;
import com.backend.boundedContext.member.domain.InfluencerStatus;
import com.backend.boundedContext.member.in.dto.InfluencerApplyRequest;
import com.backend.boundedContext.member.in.dto.InfluencerProfileResponse;
import com.backend.boundedContext.member.in.dto.InternalInfluencerResponse;
import com.backend.boundedContext.member.in.dto.MyInfluencerResponse;
import com.backend.global.page.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * 회원 컨텍스트의 인플루언서 기능. 판매자 기능은 {@link MemberSellerFacade}
 */
@Service
@RequiredArgsConstructor
public class MemberInfluencerFacade {

    private final MemberApplyInfluencerUseCase memberApplyInfluencerUseCase;
    private final MemberGetInfluencerUseCase memberGetInfluencerUseCase;
    private final MemberGetInfluencersUseCase memberGetInfluencersUseCase;
    private final MemberGetInfluencerDetailUseCase memberGetInfluencerDetailUseCase;
    private final MemberApproveInfluencerUseCase memberApproveInfluencerUseCase;
    private final MemberRejectInfluencerUseCase memberRejectInfluencerUseCase;
    private final MemberGetInternalInfluencerUseCase memberGetInternalInfluencerUseCase;

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

    @Transactional(readOnly = true)
    public AdminInfluencerListDto getInfluencersForAdmin(InfluencerStatus status, Pageable pageable) {
        return memberGetInfluencersUseCase.getInfluencers(status, pageable);
    }

    @Transactional(readOnly = true)
    public AdminInfluencerDetailDto getInfluencerDetail(Long influencerId) {
        return memberGetInfluencerDetailUseCase.getInfluencerDetail(influencerId);
    }

    @Transactional
    public AdminInfluencerDetailDto approveInfluencer(Long influencerId) {
        return memberApproveInfluencerUseCase.approve(influencerId);
    }

    @Transactional
    public AdminInfluencerDetailDto rejectInfluencer(Long influencerId, String reason) {
        return memberRejectInfluencerUseCase.reject(influencerId, reason);
    }

    @Transactional(readOnly = true)
    public InternalInfluencerResponse getInternalInfluencer(Long influencerId) {
        return memberGetInternalInfluencerUseCase.getInfluencer(influencerId);
    }

    @Transactional(readOnly = true)
    public List<InternalInfluencerResponse> getInternalInfluencers(Collection<Long> influencerIds) {
        return memberGetInternalInfluencerUseCase.getInfluencers(influencerIds);
    }

    @Transactional(readOnly = true)
    public InternalInfluencerResponse getApprovedInfluencerByMemberId(Long memberId) {
        return memberGetInternalInfluencerUseCase.getApprovedInfluencerByMemberId(memberId);
    }
}
