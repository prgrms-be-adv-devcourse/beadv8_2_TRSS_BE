package com.backend.boundedContext.review.app;

import com.backend.boundedContext.review.domain.Review;
import com.backend.boundedContext.review.domain.ReviewErrorCode;
import com.backend.boundedContext.review.out.ReviewRepository;
import com.backend.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReviewSupport {
    private final ReviewRepository reviewRepository;

    /** 회원 본인의 삭제되지 않은 리뷰. 없거나 삭제됐거나 다른 회원의 리뷰면 404 REVIEW_NOT_FOUND */
    public Review getMyReview(Long memberId, Long reviewId) {
        return reviewRepository.findActiveByIdAndMemberId(reviewId, memberId)
                .orElseThrow(() -> new DomainException(ReviewErrorCode.REVIEW_NOT_FOUND));
    }

    /**
     * 리뷰에 보여줄 작성자 이름. 첫 글자와 끝 글자만 남기고 가운데를 *로 가린다.
     * 1자는 "*", 2자는 첫 글자 + "*" (김진 → 김*), 3자 이상은 가운데 글자 수만큼 * (김유진 → 김*진, 남궁민희 → 남**희).
     * 앞뒤 공백은 지우고, 이름이 비어 있으면 "*"
     */
    public static String maskName(String name) {
        if (name == null || name.isBlank()) return "*";

        int[] chars = name.strip().codePoints().toArray();
        if (chars.length == 1) return "*";
        if (chars.length == 2) return new String(chars, 0, 1) + "*";

        return new String(chars, 0, 1) + "*".repeat(chars.length - 2) + new String(chars, chars.length - 1, 1);
    }
}
