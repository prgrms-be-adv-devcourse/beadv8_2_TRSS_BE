package com.backend.boundedContext.settlement.in.controller;

import com.backend.boundedContext.settlement.app.SettlementFacade;
import com.backend.boundedContext.settlement.domain.SettlementCandidateStatus;
import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import com.backend.boundedContext.settlement.in.dto.SettlementCandidateExcludeRequest;
import com.backend.boundedContext.settlement.in.dto.SettlementCandidateResponse;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 정산 후보 API (목록, 제외)
 *
 * /api/v1/admin/** 은 SecurityConfig에서 ADMIN만 들어올 수 있다
 */
@Tag(name = "관리자 정산 후보", description = "정산 후보 목록·제외")
@RestController
@RequestMapping("/api/v1/admin/settlement-candidates")
@RequiredArgsConstructor
public class ApiV1AdminSettlementCandidateController {

    private final SettlementFacade settlementFacade;

    @Operation(summary = "정산 후보 목록", description = "판매자·정산월·상태로 거를 수 있다(모두 선택), 최신순")
    @ApiResponse(responseCode = "200", description = "200-1 조회 성공")
    @ApiResponse(responseCode = "400", description = "400-VALIDATION_FAILED 정산월 형식 오류")
    @GetMapping
    public RsData<PageResponse<SettlementCandidateResponse>> getCandidates(
            @RequestParam(required = false) @Positive Long sellerId,
            @RequestParam(required = false) @Pattern(regexp = SettlementPolicy.MONTH_PATTERN) String month,
            @RequestParam(required = false) SettlementCandidateStatus status,
            @ParameterObject Pageable pageable) {
        return RsData.of("200-1", "정산 후보 목록을 조회했습니다.",
                settlementFacade.getCandidates(sellerId, month, status, pageable));
    }

    @Operation(summary = "정산 후보 제외",
            description = "아직 정산서에 묶이지 않은 READY 후보를 EXCLUDED로 바꾼다(사유 필수). 제외된 금액은 집계·미정산 금액에서 빠진다")
    @ApiResponse(responseCode = "200", description = "200-1 제외 완료")
    @ApiResponse(responseCode = "400", description = "400-VALIDATION_FAILED 사유 없음")
    @ApiResponse(responseCode = "404", description = "404-SETTLEMENT_CANDIDATE_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "409-SETTLEMENT_CANDIDATE_STATE_INVALID READY가 아님")
    @PostMapping("/{candidateId}/exclude")
    public RsData<SettlementCandidateResponse> exclude(@PathVariable Long candidateId,
                                                       @Valid @RequestBody SettlementCandidateExcludeRequest request) {
        return RsData.of("200-1", "정산 후보를 제외했습니다.",
                settlementFacade.excludeCandidate(candidateId, request.reason()));
    }
}