package com.backend.boundedContext.settlement.in.controller;

import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.app.SettlementFacade;
import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.in.dto.*;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.List;

/**
 * 관리자 정산 API
 *
 * /api/v1/admin/** 은 SecurityConfig에서 ADMIN만 들어올 수 있다
 */
@Tag(name = "관리자 정산", description = "월 정산 실행·조회·수동 재처리·보류·해제·처리 이력")
@RestController
@RequestMapping("/api/v1/admin/settlements")
@RequiredArgsConstructor
public class ApiV1AdminSettlementController {

    private final SettlementFacade settlementFacade;

    @Operation(summary = "월 정산 수동 실행",
            description = "settleMonth(종료된 달만)로 집계 → 지급 Job을 동기로 실행하고 결과를 돌려준다. 실패로 끝난 달은 이어서 실행된다")
    @ApiResponse(responseCode = "200", description = "200-1 실행 완료(data.status로 COMPLETED·FAILED 구분)")
    @ApiResponse(responseCode = "400", description = "400-SETTLEMENT_MONTH_INVALID 끝나지 않은 달 / 400-VALIDATION_FAILED 형식 오류")
    @ApiResponse(responseCode = "409", description = "409-SETTLEMENT_RUNNING 같은 달 실행 중 / 409-SETTLEMENT_ALREADY_COMPLETED 이미 완료된 달")
    @PostMapping("/runs")
    public RsData<SettlementRunResponse> run(@Valid @RequestBody SettlementRunRequest request) {
        return RsData.of("200-1", "월 정산을 실행했습니다.",
                settlementFacade.runMonthlyJob(YearMonth.parse(request.settleMonth()), SettlementTrigger.ADMIN));
    }

    @Operation(summary = "월 정산 실행 결과", description = "정산월·실행 시각·상태, 성공·실패 건수, 지급·수수료 총액 (최신 실행부터)")
    @ApiResponse(responseCode = "200", description = "200-1 조회 성공")
    @GetMapping("/runs")
    public RsData<PageResponse<SettlementRunResponse>> getRuns(Pageable pageable) {
        return RsData.of("200-1", "정산 실행 결과를 조회했습니다.",
                settlementFacade.getRuns(pageable.getPageNumber(), pageable.getPageSize()));
    }

    @Operation(summary = "정산서 목록", description = "정산월·상태·판매자로 거를 수 있다(모두 선택). 실패 사유·시도 횟수·다음 재시도 일시 포함")
    @ApiResponse(responseCode = "200", description = "200-1 조회 성공")
    @GetMapping
    public RsData<PageResponse<SettlementResponse>> getSettlements(
            @RequestParam(required = false) @Pattern(regexp = SettlementPolicy.MONTH_PATTERN) String month,
            @RequestParam(required = false) SettlementStatus status,
            @RequestParam(required = false) @Positive Long sellerId,
            Pageable pageable) {
        return RsData.of("200-1", "정산서 목록을 조회했습니다.",
                settlementFacade.getSettlements(month, status, sellerId, pageable));
    }

    @Operation(summary = "정산서 상세", description = "금액, 묶인 후보, 최근 실패 사유, 시도 횟수, 다음 재시도 일시, 보류 사유")
    @ApiResponse(responseCode = "200", description = "200-1 조회 성공")
    @ApiResponse(responseCode = "404", description = "404-SETTLEMENT_NOT_FOUND")
    @GetMapping("/{settlementId}")
    public RsData<SettlementDetailResponse> getSettlement(@PathVariable Long settlementId) {
        return RsData.of("200-1", "정산서를 조회했습니다.", settlementFacade.getSettlement(settlementId));
    }

    @Operation(summary = "수동 재처리",
            description = "MANUAL_REQUIRED 정산서 1건을 자동 실행과 같은 지급 로직으로 다시 지급(사유 필수). 성공 PAID, 실패 MANUAL_REQUIRED 유지(data.status로 구분)")
    @ApiResponse(responseCode = "200", description = "200-1 재처리 완료")
    @ApiResponse(responseCode = "400", description = "400-VALIDATION_FAILED 사유 없음")
    @ApiResponse(responseCode = "404", description = "404-SETTLEMENT_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "409-SETTLEMENT_STATE_INVALID MANUAL_REQUIRED가 아님")
    @PostMapping("/{settlementId}/retry")
    public RsData<SettlementPayoutResult> retry(@PathVariable Long settlementId,
                                                @Valid @RequestBody SettlementRetryRequest request) {
        return RsData.of("200-1", "정산 재처리를 마쳤습니다.",
                settlementFacade.retry(settlementId, request.reason()));
    }

    @Operation(summary = "정산서 처리 이력", description = "정산서 1건의 집계·지급·보류·해제 이력(성공·실패·건너뜀)을 처리 순서대로")
    @ApiResponse(responseCode = "200", description = "200-1 조회 성공")
    @ApiResponse(responseCode = "404", description = "404-SETTLEMENT_NOT_FOUND")
    @GetMapping("/{settlementId}/histories")
    public RsData<List<SettlementHistoryResponse>> getHistories(@PathVariable Long settlementId) {
        return RsData.of("200-1", "정산 처리 이력을 조회했습니다.", settlementFacade.getHistories(settlementId));
    }

    @Operation(summary = "정산 보류",
            description = "지급 전 정산서(READY·FAILED·MANUAL_REQUIRED)를 HOLD로 바꾼다(사유 필수). HOLD 동안에는 어떤 경로로도 지급하지 않는다")
    @ApiResponse(responseCode = "200", description = "200-1 보류 완료")
    @ApiResponse(responseCode = "400", description = "400-VALIDATION_FAILED 사유 없음")
    @ApiResponse(responseCode = "404", description = "404-SETTLEMENT_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "409-SETTLEMENT_STATE_INVALID 이미 PAID·HOLD")
    @PostMapping("/{settlementId}/hold")
    public RsData<SettlementResponse> hold(@PathVariable Long settlementId,
                                           @Valid @RequestBody SettlementHoldRequest request) {
        return RsData.of("200-1", "정산을 보류했습니다.", settlementFacade.hold(settlementId, request.reason()));
    }

    @Operation(summary = "정산 보류 해제",
            description = "HOLD 정산서를 READY로 되돌린다. 지급은 10분마다 도는 재시도 스케줄러가 한다")
    @ApiResponse(responseCode = "200", description = "200-1 해제 완료")
    @ApiResponse(responseCode = "404", description = "404-SETTLEMENT_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "409-SETTLEMENT_STATE_INVALID HOLD가 아님")
    @PostMapping("/{settlementId}/release")
    public RsData<SettlementResponse> release(@PathVariable Long settlementId) {
        return RsData.of("200-1", "정산 보류를 해제했습니다.", settlementFacade.release(settlementId));
    }

}