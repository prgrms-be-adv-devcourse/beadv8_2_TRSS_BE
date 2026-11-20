package com.backend.boundedContext.review.app;

import com.backend.boundedContext.review.out.ReviewRepository;
import com.backend.shared.review.dto.MemberRatingDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 회원이 쓴 리뷰의 별점 (ReviewApi, 상품 컨텍스트의 개인화 추천 기준 상품) */
@Service
@RequiredArgsConstructor
public class ReviewGetMemberRatingsUseCase {
    private final ReviewRepository reviewRepository;

    public List<MemberRatingDto> getMemberRatings(Long memberId) {
        return reviewRepository.findRatingsByMemberId(memberId);
    }
}
