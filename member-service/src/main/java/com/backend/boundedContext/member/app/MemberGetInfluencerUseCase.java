package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.domain.Influencer;
import com.backend.boundedContext.member.domain.InfluencerStatus;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.in.dto.InfluencerProfileResponse;
import com.backend.boundedContext.member.in.dto.MyInfluencerResponse;
import com.backend.boundedContext.member.out.InfluencerRepository;
import com.backend.boundedContext.member.out.InfluencerStatusHistoryRepository;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.page.PageResponse;
import com.backend.shared.member.dto.SellerStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MemberGetInfluencerUseCase {
    private final InfluencerRepository influencerRepository;
    private final InfluencerStatusHistoryRepository influencerStatusHistoryRepository;
    private final SellerRepository sellerRepository;

    public MyInfluencerResponse getMyInfluencer(Long memberId) {
        Influencer influencer = influencerRepository.findByMemberId(memberId)
                .orElseThrow(() -> new DomainException(MemberErrorCode.INFLUENCER_NOT_FOUND));
        return MyInfluencerResponse.from(
                influencer,
                influencerStatusHistoryRepository.findFirstByInfluencerIdOrderByIdDesc(influencer.getId()).orElse(null)
        );
    }

    public PageResponse<InfluencerProfileResponse> search(Long memberId, String keyword, Pageable pageable) {
        sellerRepository.findByMemberId(memberId)
                .filter(seller -> seller.getStatus() == SellerStatus.APPROVED)
                .orElseThrow(() -> new DomainException(MemberErrorCode.SELLER_NOT_APPROVED));

        String activityNameKeyword = keyword == null ? "" : keyword.strip();
        return PageResponse.from(
                influencerRepository.findByStatusAndActivityNameContainingIgnoreCaseOrderByIdDesc(InfluencerStatus.APPROVED, activityNameKeyword, pageable),
                InfluencerProfileResponse::from
        );
    }

    public InfluencerProfileResponse getProfile(Long influencerId) {
        return influencerRepository.findByIdAndStatus(influencerId, InfluencerStatus.APPROVED)
                .map(InfluencerProfileResponse::from)
                .orElseThrow(() -> new DomainException(MemberErrorCode.INFLUENCER_NOT_FOUND));
    }
}
