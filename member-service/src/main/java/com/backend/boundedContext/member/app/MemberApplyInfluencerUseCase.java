package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.domain.Influencer;
import com.backend.boundedContext.member.domain.InfluencerStatusHistory;
import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.in.dto.InfluencerApplyRequest;
import com.backend.boundedContext.member.in.dto.MyInfluencerResponse;
import com.backend.boundedContext.member.out.InfluencerRepository;
import com.backend.boundedContext.member.out.InfluencerStatusHistoryRepository;
import com.backend.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 인플루언서 신청. 처음이면 인플루언서를 만들고, 반려 뒤라면 같은 인플루언서로 재신청한다.
 * 같은 회원의 처음 신청이 동시에 들어오면 member_id UNIQUE가 하나만 남긴다.
 */
@Component
@RequiredArgsConstructor
public class MemberApplyInfluencerUseCase {
    private final InfluencerRepository influencerRepository;
    private final InfluencerStatusHistoryRepository influencerStatusHistoryRepository;
    private final MemberSupport memberSupport;

    public MyInfluencerResponse apply(Long memberId, InfluencerApplyRequest request) {
        Member member = memberSupport.getMember(memberId);

        return influencerRepository.findByMemberId(memberId)
                .map(influencer -> applyAgain(influencer, request))
                .orElseGet(() -> applyFirst(member, request));
    }

    private MyInfluencerResponse applyFirst(Member member, InfluencerApplyRequest request) {
        Influencer influencer = Influencer.apply(member, request.activityName(), request.snsUrl(), request.introduction());
        try {
            influencerRepository.saveAndFlush(influencer);
        } catch (DataIntegrityViolationException e) {
            // 조회한 뒤 같은 회원의 신청이 먼저 커밋됨 (더블 클릭 등)
            throw new DomainException(MemberErrorCode.INFLUENCER_ALREADY_APPLIED, e);
        }
        InfluencerStatusHistory history = influencerStatusHistoryRepository.save(influencer.recordApplied());
        return MyInfluencerResponse.from(influencer, history);
    }

    private MyInfluencerResponse applyAgain(Influencer influencer, InfluencerApplyRequest request) {
        InfluencerStatusHistory history = influencer.applyAgain(request.activityName(), request.snsUrl(), request.introduction());
        influencerStatusHistoryRepository.save(history);
        return MyInfluencerResponse.from(influencerRepository.saveAndFlush(influencer), history);
    }
}
