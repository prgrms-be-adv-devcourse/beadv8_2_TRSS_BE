package com.backend.boundedContext.recommend.in;

import com.backend.boundedContext.recommend.app.RecommendFacade;
import com.backend.boundedContext.recommend.app.dto.BeautyProfileDto;
import com.backend.boundedContext.recommend.in.dto.SurveySaveRequest;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "뷰티 설문", description = "내 뷰티 프로필(피부 타입·고민·선호 성분·피하는 성분·관심 카테고리) 저장과 조회")
@RestController
@RequestMapping("/api/v1/ai/survey")
@RequiredArgsConstructor
public class ApiV1SurveyController {

    private final RecommendFacade recommendFacade;

    @Operation(summary = "뷰티 설문 저장",
            description = "처음이면 만들고 있으면 덮어씁니다. 값은 코드로 보내고, 고르지 않은 칸은 빈 배열로 보냅니다. "
                    + "피부 고민은 최대 3개, 선호 성분에는 선호 코드만, 피하는 성분에는 피하는 성분 코드만 올 수 있습니다.")
    @ApiResponse(responseCode = "200", description = "200-1 저장")
    @ApiResponse(responseCode = "400", description = "400-VALIDATION_FAILED 누락·고민 4개 이상, 400-INVALID_REQUEST 모르는 코드, 400-SURVEY_ANSWER_INVALID 칸에 맞지 않는 성분·중복")
    @PutMapping("/me")
    public RsData<BeautyProfileDto> saveSurvey(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody SurveySaveRequest request
    ) {
        return RsData.of("200-1", "설문을 저장했습니다.", recommendFacade.saveSurvey(authMember.memberId(), request.skinType(),
                request.concerns(), request.preferredIngredients(), request.avoidedIngredients(), request.interestCategories()));
    }

    @Operation(summary = "내 뷰티 프로필", description = "설문을 아직 하지 않았으면 404입니다.")
    @ApiResponse(responseCode = "200", description = "200-1 조회")
    @ApiResponse(responseCode = "404", description = "404-SURVEY_NOT_FOUND 설문을 아직 하지 않음")
    @GetMapping("/me")
    public RsData<BeautyProfileDto> getSurvey(@AuthenticationPrincipal AuthMember authMember) {
        return RsData.of("200-1", "설문을 조회했습니다.", recommendFacade.getSurvey(authMember.memberId()));
    }
}
