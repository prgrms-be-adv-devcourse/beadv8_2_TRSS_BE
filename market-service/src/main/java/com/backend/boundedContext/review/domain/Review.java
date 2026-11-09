package com.backend.boundedContext.review.domain;

import com.backend.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 구매 확정된 주문 항목에 남긴 상품 리뷰. 주문 항목 하나에 한 건만 둔다 (uk_review_order_item, 삭제한 리뷰 포함).
 * 회원·상품·주문 항목은 다른 컨텍스트라 ID로만 참조한다.
 * 옵션명과 작성자 이름은 작성 시점 값을 그대로 남긴다 (옵션이 바뀌거나 회원이 이름을 바꿔도 리뷰는 그대로).
 */
@Entity
@Table(name = "review")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Review extends BaseIdAndTime {

    public static final int MIN_RATING = 1;
    public static final int MAX_RATING = 5;
    public static final int MIN_CONTENT_LENGTH = 10;
    public static final int MAX_CONTENT_LENGTH = 1_000;

    @Column(nullable = false, unique = true)
    private Long orderItemId;

    @Column(nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private Long optionId;

    @Column(nullable = false, length = 50)
    private String optionName;

    @Column(nullable = false, length = 50)
    private String reviewerName;

    @Column(nullable = false)
    private int rating;

    @Column(nullable = false, length = MAX_CONTENT_LENGTH)
    private String content;

    private LocalDateTime deletedAt;

    public static Review create(Long orderItemId, Long memberId, Long productId, Long optionId, String optionName,
                                String reviewerName, int rating, String content) {
        Review review = new Review();
        review.orderItemId = orderItemId;
        review.memberId = memberId;
        review.productId = productId;
        review.optionId = optionId;
        review.optionName = optionName;
        review.reviewerName = reviewerName;
        review.rating = rating;
        review.content = content;
        return review;
    }

    /** 보낸 값만 바꾼다 (null이면 그대로). 값 범위는 요청 검증에서 확인한다 */
    public void modify(Integer rating, String content) {
        if (rating != null) this.rating = rating;
        if (content != null) this.content = content;
    }

    public void delete(LocalDateTime now) {
        this.deletedAt = now;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isWrittenBy(Long memberId) {
        return this.memberId.equals(memberId);
    }

    /** 작성 후 내용을 고친 적이 있는지 (생성 시각과 수정 시각이 다르면 수정된 것) */
    public boolean isModified() {
        return getUpdatedAt() != null && getCreatedAt() != null && getUpdatedAt().isAfter(getCreatedAt());
    }
}
