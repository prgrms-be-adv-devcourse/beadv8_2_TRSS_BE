package com.backend.boundedContext.review.in;

import com.backend.boundedContext.review.app.ReviewFacade;
import com.backend.boundedContext.review.app.dto.MyReviewDto;
import com.backend.boundedContext.review.in.dto.ReviewCreateRequest;
import com.backend.boundedContext.review.in.dto.ReviewCreateResponse;
import com.backend.boundedContext.review.in.dto.ReviewModifyRequest;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "리뷰", description = "구매자의 리뷰 작성·수정·삭제와 내 리뷰 목록")
@RestController
@RequestMapping("/api/v1/reviews")
@RequiredArgsConstructor
public class ApiV1ReviewController {
    private final ReviewFacade reviewFacade;

    @Operation(summary = "리뷰 작성",
            description = "구매 확정된 본인 주문 항목에 리뷰를 씁니다. 주문 항목 하나에 리뷰 하나이며, 삭제한 리뷰가 있던 항목에는 다시 쓸 수 없습니다.")
    @ApiResponse(responseCode = "201", description = "201-1 리뷰 작성")
    @ApiResponse(responseCode = "404", description = "404-ORDER_ITEM_NOT_REVIEWABLE 없거나 본인 주문이 아니거나 구매 확정 전·취소·반품된 주문 항목")
    @ApiResponse(responseCode = "409", description = "409-REVIEW_ALREADY_EXISTS 이미 리뷰를 쓴 주문 항목")
    @PostMapping
    public RsData<ReviewCreateResponse> createReview(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody ReviewCreateRequest request
    ) {
        Long reviewId = reviewFacade.createReview(authMember.memberId(), request.orderItemId(),
                request.rating(), request.content());
        return RsData.of("201-1", "리뷰를 작성했습니다.", new ReviewCreateResponse(reviewId));
    }

    @Operation(summary = "내 리뷰 목록", description = "최근에 쓴 순서입니다. 삭제한 리뷰는 나오지 않습니다.")
    @GetMapping("/me")
    public RsData<PageResponse<MyReviewDto>> getMyReviews(
            @AuthenticationPrincipal AuthMember authMember,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "내 리뷰 목록을 조회했습니다.",
                reviewFacade.getMyReviews(authMember.memberId(), pageable));
    }

    @Operation(summary = "리뷰 수정", description = "보낸 값만 바꿉니다. 별점과 내용 중 하나는 보내야 합니다.")
    @ApiResponse(responseCode = "200", description = "200-1 리뷰 수정")
    @ApiResponse(responseCode = "404", description = "404-REVIEW_NOT_FOUND 없거나 삭제됐거나 본인 리뷰가 아님")
    @PatchMapping("/{reviewId}")
    public RsData<Void> modifyReview(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long reviewId,
            @Valid @RequestBody ReviewModifyRequest request
    ) {
        reviewFacade.modifyReview(authMember.memberId(), reviewId, request.rating(), request.content());
        return RsData.of("200-1", "리뷰를 수정했습니다.");
    }

    @Operation(summary = "리뷰 삭제", description = "삭제한 리뷰의 주문 항목에는 다시 리뷰를 쓸 수 없습니다.")
    @ApiResponse(responseCode = "200", description = "200-1 리뷰 삭제")
    @ApiResponse(responseCode = "404", description = "404-REVIEW_NOT_FOUND 없거나 이미 삭제됐거나 본인 리뷰가 아님")
    @DeleteMapping("/{reviewId}")
    public RsData<Void> deleteReview(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long reviewId
    ) {
        reviewFacade.deleteReview(authMember.memberId(), reviewId);
        return RsData.of("200-1", "리뷰를 삭제했습니다.");
    }
}
