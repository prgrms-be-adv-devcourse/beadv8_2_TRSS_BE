package com.backend.boundedContext.review.in;

import com.backend.boundedContext.review.app.ReviewFacade;
import com.backend.shared.review.dto.MemberRatingDto;
import com.backend.shared.review.dto.ProductRatingStatsDto;
import com.backend.shared.review.out.ReviewApi;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ReviewApiAdapter implements ReviewApi {

    @Lazy   // 컨텍스트 간 순환 참조 방지 (Review → OrderApi·ProductApi → Product → ReviewApi)
    private final ReviewFacade reviewFacade;

    @Override
    public Map<Long, ProductRatingStatsDto> getRatingStats(Collection<Long> productIds) {
        return reviewFacade.getRatingStats(productIds);
    }

    @Override
    public List<MemberRatingDto> getMemberRatings(Long memberId) {
        return reviewFacade.getMemberRatings(memberId);
    }
}
