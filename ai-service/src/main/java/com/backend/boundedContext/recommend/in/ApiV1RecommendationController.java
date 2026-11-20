package com.backend.boundedContext.recommend.in;

import com.backend.boundedContext.recommend.app.RecommendFacade;
import com.backend.boundedContext.recommend.app.dto.CreatorRecommendationsDto;
import com.backend.boundedContext.recommend.app.dto.ProductRecommendationsDto;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "맞춤 추천", description = "뷰티 설문 기반 상품 추천")
@RestController
@RequestMapping("/api/v1/ai/recommendations")
@RequiredArgsConstructor
public class ApiV1RecommendationController {

    private final RecommendFacade recommendFacade;

    @Operation(
            summary = "맞춤 상품 추천",
            description = "뷰티 설문이 있으면 피하는 성분이 든 상품(과 전성분을 모르는 상품)을 빼고, 피부 프로필과 비슷한 순서로 추천 이유와 함께 줍니다. "
                    + "설문이 없거나 로그인하지 않았으면 personalized=false이고 판매 중인 뷰티 상품을 최신순으로 줍니다. "
                    + "가격은 정가이고, 할인·품절은 상품 상세에서 봅니다.")
    @GetMapping
    public RsData<ProductRecommendationsDto> getProducts(
            @AuthenticationPrincipal AuthMember authMember,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size
    ) {
        Long memberId = authMember == null ? null : authMember.memberId();
        return RsData.of("200-1", "추천 상품을 조회했습니다.", recommendFacade.getProducts(memberId, size));
    }

    @Operation(
            summary = "피부가 비슷한 크리에이터 추천",
            description = "승인된 인플루언서 중 뷰티 설문을 한 사람을 내 설문과 겹치는 정도(피부 타입 + 겹친 고민 수 + 겹친 선호 성분 수)가 큰 순서로 줍니다. "
                    + "겹치는 게 없는 사람은 빠집니다. 로그인이 필요하고, 설문을 안 했으면 404-SURVEY_NOT_FOUND입니다.")
    @ApiResponse(responseCode = "404", description = "404-SURVEY_NOT_FOUND 설문을 아직 하지 않음")
    @ApiResponse(responseCode = "502", description = "502-EXTERNAL_SERVICE_ERROR 회원 서비스가 응답하지 않음")
    @GetMapping("/creators")
    public RsData<CreatorRecommendationsDto> getCreators(
            @AuthenticationPrincipal AuthMember authMember,
            @RequestParam(defaultValue = "10") @Min(1) @Max(20) int size
    ) {
        return RsData.of("200-1", "추천 크리에이터를 조회했습니다.", recommendFacade.getCreators(authMember.memberId(), size));
    }
}
