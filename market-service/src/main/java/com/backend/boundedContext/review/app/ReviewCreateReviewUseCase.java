package com.backend.boundedContext.review.app;

import com.backend.boundedContext.review.domain.Review;
import com.backend.boundedContext.review.domain.ReviewErrorCode;
import com.backend.boundedContext.review.out.ReviewRepository;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.shared.member.dto.MemberDto;
import com.backend.shared.member.dto.MemberStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.dto.ReviewableOrderItemDto;
import com.backend.shared.order.out.OrderApi;
import com.backend.shared.review.event.ReviewCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReviewCreateReviewUseCase {
    private final ReviewRepository reviewRepository;
    private final OrderApi orderApi;
    private final MemberApi memberApi;
    private final EventPublisher eventPublisher;

    /**
     * 구매 확정된 본인 주문 항목에 리뷰를 쓰고 리뷰 ID를 돌려준다.
     * <ul>
     *   <li>없는 항목·다른 회원 주문·확정 전·취소·반품이면 404 ORDER_ITEM_NOT_REVIEWABLE</li>
     *   <li>이미 리뷰를 쓴 항목이면 (삭제한 리뷰 포함) 409 REVIEW_ALREADY_EXISTS. 동시에 써도 유니크 제약으로 한 건만 남는다</li>
     *   <li>탈퇴한 회원이면 401 UNAUTHORIZED (탈퇴 후에도 Access Token은 만료 전까지 유효)</li>
     *   <li>상품·옵션은 클라이언트 값이 아니라 주문 항목에서 가져온다</li>
     * </ul>
     */
    public Long createReview(Long memberId, Long orderItemId, int rating, String content) {
        ReviewableOrderItemDto item = orderApi.findConfirmedOrderItem(memberId, orderItemId)
                .orElseThrow(() -> new DomainException(ReviewErrorCode.ORDER_ITEM_NOT_REVIEWABLE));

        if (reviewRepository.existsByOrderItemId(orderItemId)) {
            throw new DomainException(ReviewErrorCode.REVIEW_ALREADY_EXISTS);
        }

        MemberDto member = memberApi.getMember(memberId);
        if (member.status() == MemberStatus.DELETED) {
            throw new DomainException(GlobalErrorCode.UNAUTHORIZED);
        }

        Review review = Review.create(item.orderItemId(), memberId, item.productId(), item.optionId(),
                item.optionName(), ReviewSupport.maskName(member.name()), rating, content);
        try {
            reviewRepository.saveAndFlush(review);
        } catch (DataIntegrityViolationException e) {
            // 위의 중복 검사를 동시에 통과한 요청. 이 트랜잭션은 롤백된다
            throw new DomainException(ReviewErrorCode.REVIEW_ALREADY_EXISTS, e);
        }

        eventPublisher.publish(new ReviewCreatedEvent(review.getId(), review.getProductId(), review.getRating()));
        return review.getId();
    }
}
