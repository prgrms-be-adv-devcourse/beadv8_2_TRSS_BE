package com.backend.boundedContext.recommend.app;

import com.backend.boundedContext.recommend.app.dto.BeautyProfileDto;
import com.backend.boundedContext.recommend.domain.RecommendErrorCode;
import com.backend.boundedContext.recommend.out.BeautyProfileRepository;
import com.backend.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RecommendGetSurveyUseCase {
    private final BeautyProfileRepository beautyProfileRepository;

    // 설문을 아직 안 했으면 404 (프론트는 설문 화면으로 안내)
    public BeautyProfileDto get(Long memberId) {
        return beautyProfileRepository.findByMemberId(memberId)
                .map(BeautyProfileDto::from)
                .orElseThrow(() -> new DomainException(RecommendErrorCode.SURVEY_NOT_FOUND));
    }
}
