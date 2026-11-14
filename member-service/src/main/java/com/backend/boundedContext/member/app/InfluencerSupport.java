package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.app.dto.AdminInfluencerDetailDto;
import com.backend.boundedContext.member.domain.Influencer;
import com.backend.boundedContext.member.domain.InfluencerStatusHistory;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.out.InfluencerRepository;
import com.backend.boundedContext.member.out.InfluencerStatusHistoryRepository;
import com.backend.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 관리자 인플루언서 UseCase들이 함께 쓰는 조회·이력 저장
@Component
@RequiredArgsConstructor
public class InfluencerSupport {
    private final InfluencerRepository influencerRepository;
    private final InfluencerStatusHistoryRepository influencerStatusHistoryRepository;

    public Influencer getInfluencer(Long influencerId) {
        return influencerRepository.findById(influencerId)
                .orElseThrow(() -> new DomainException(MemberErrorCode.INFLUENCER_NOT_FOUND));
    }

    // influencer는 조회한 엔티티라 커밋 때 변경 감지로 저장된다
    public AdminInfluencerDetailDto saveHistory(Influencer influencer, InfluencerStatusHistory history) {
        influencerStatusHistoryRepository.save(history);
        return getInfluencerDetail(influencer);
    }

    public AdminInfluencerDetailDto getInfluencerDetail(Influencer influencer) {
        return AdminInfluencerDetailDto.of(influencer, influencerStatusHistoryRepository.findAllByInfluencerIdOrderByIdAsc(influencer.getId()));
    }
}
