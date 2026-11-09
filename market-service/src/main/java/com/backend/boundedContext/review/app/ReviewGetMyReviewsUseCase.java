package com.backend.boundedContext.review.app;

import com.backend.boundedContext.review.app.dto.MyReviewDto;
import com.backend.boundedContext.review.domain.Review;
import com.backend.boundedContext.review.out.ReviewRepository;
import com.backend.global.page.PageResponse;
import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.out.ProductApi;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReviewGetMyReviewsUseCase {
    private final ReviewRepository reviewRepository;
    private final ProductApi productApi;

    /**
     * 내 리뷰 목록 (최신순, 삭제된 리뷰 제외). 정렬은 요청 값과 관계없이 최신순으로 고정한다.
     * 상품명·대표 이미지는 현재 상품 정보를 옵션 ID로 한 번에 조회해서 채운다 (삭제된 상품도 포함된다)
     */
    public PageResponse<MyReviewDto> getMyReviews(Long memberId, Pageable pageable) {
        Pageable latest = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.desc("id")));
        Page<Review> reviews = reviewRepository.findActivePageByMemberId(memberId, latest);

        Set<Long> optionIds = reviews.getContent().stream().map(Review::getOptionId).collect(Collectors.toSet());
        Map<Long, OptionSnapshotDto> options = optionIds.isEmpty() ? Map.of()
                : productApi.getOptions(optionIds).stream()
                        .collect(Collectors.toMap(OptionSnapshotDto::optionId, Function.identity()));

        return PageResponse.from(reviews, review -> {
            OptionSnapshotDto option = options.get(review.getOptionId());
            return new MyReviewDto(
                    review.getId(), review.getOrderItemId(), review.getProductId(),
                    option == null ? null : option.productName(),
                    option == null ? null : option.thumbnailUrl(),
                    review.getOptionName(), review.getRating(), review.getContent(),
                    review.isModified(), review.getCreatedAt());
        });
    }
}
