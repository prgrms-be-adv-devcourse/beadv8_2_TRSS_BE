package com.backend.boundedContext.settlement.in.controller;

import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.app.SettlementFacade;
import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import com.backend.boundedContext.settlement.domain.SettlementAdminStatusGroup;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.in.dto.SettlementLedgerMismatchConfirmRequest;
import com.backend.boundedContext.settlement.in.dto.SettlementLedgerMismatchConfirmResponse;
import com.backend.boundedContext.settlement.in.dto.SettlementDetailResponse;
import com.backend.boundedContext.settlement.in.dto.SettlementHistoryResponse;
import com.backend.boundedContext.settlement.in.dto.SettlementHoldRequest;
import com.backend.boundedContext.settlement.in.dto.SettlementPayoutResult;
import com.backend.boundedContext.settlement.in.dto.SettlementPreviewResponse;
import com.backend.boundedContext.settlement.in.dto.SettlementResponse;
import com.backend.boundedContext.settlement.in.dto.SettlementRetryRequest;
import com.backend.boundedContext.settlement.in.dto.SettlementRunRequest;
import com.backend.boundedContext.settlement.in.dto.SettlementRunResponse;
import com.backend.boundedContext.settlement.in.dto.SettlementRunnableMonthResponse;
import com.backend.boundedContext.settlement.in.dto.SettlementSummaryResponse;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
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
@Tag(name = "관리자 정산", description = "월 정산 실행·조회·미리보기·수동 재처리·보류·해제·처리 이력")
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
            @Parameter(description = "기간 조회 시작 정산월 yyyy-MM (to와 함께, month와 같이 쓸 수 없음)")
            @RequestParam(required = false) @Pattern(regexp = SettlementPolicy.MONTH_PATTERN) String from,
            @Parameter(description = "기간 조회 끝 정산월 yyyy-MM (포함, 최대 maxSearchMonths개월)")
            @RequestParam(required = false) @Pattern(regexp = SettlementPolicy.MONTH_PATTERN) String to,
            @Parameter(description = "정산서 상태 1개 (group보다 우선)")
            @RequestParam(required = false) SettlementStatus status,
            @Parameter(description = "화면 상태 탭: ACTION_REQUIRED(처리 필요 = FAILED·MANUAL_REQUIRED) · SCHEDULED · PAID · ON_HOLD")
            @RequestParam(required = false) SettlementAdminStatusGroup group,
            @RequestParam(required = false) @Positive Long sellerId,
            Pageable pageable) {
        return RsData.of("200-1", "정산서 목록을 조회했습니다.", settlementFacade.getSettlements(month, monthOf(from),
                monthOf(to), status, group, sellerId, pageable));
    }

    @Operation(summary = "정산 요약 카드",
            description = "정산월 또는 기간(from~to)·판매자 기준 상태별 건수와 지급 완료 금액. 상태 탭과 관계없이 모든 상태를 센다")
    @ApiResponse(responseCode = "200", description = "200-1 조회 성공")
    @ApiResponse(responseCode = "400", description = "400-SETTLEMENT_MONTH_RANGE_INVALID 기간 오류 / 400-VALIDATION_FAILED 형식 오류")
    @GetMapping("/summary")
    public RsData<SettlementSummaryResponse> getSummary(
            @Parameter(description = "정산월 yyyy-MM")
            @RequestParam(required = false) @Pattern(regexp = SettlementPolicy.MONTH_PATTERN) String month,
            @Parameter(description = "기간 조회 시작 정산월 yyyy-MM (to와 함께, month와 같이 쓸 수 없음)")
            @RequestParam(required = false) @Pattern(regexp = SettlementPolicy.MONTH_PATTERN) String from,
            @Parameter(description = "기간 조회 끝 정산월 yyyy-MM (포함, 최대 maxSearchMonths개월)")
            @RequestParam(required = false) @Pattern(regexp = SettlementPolicy.MONTH_PATTERN) String to,
            @RequestParam(required = false) @Positive Long sellerId) {
        return RsData.of("200-1", "정산 요약을 조회했습니다.",
                settlementFacade.getSummary(month, monthOf(from), monthOf(to), sellerId));
    }

    @Operation(summary = "수동 실행할 수 있는 달",
            description = "자동 실행이 실패·중단된 달(최근 maxSearchMonths개월)과, 실행 시각이 지났는데 실행되지 않은 지난달. 최근 달부터")
    @ApiResponse(responseCode = "200", description = "200-1 조회 성공")
    @GetMapping("/runs/runnable")
    public RsData<List<SettlementRunnableMonthResponse>> getRunnableMonths() {
        return RsData.of("200-1", "수동 실행할 수 있는 달을 조회했습니다.", settlementFacade.getRunnableMonths());
    }

    @Operation(summary = "지급 완료 정산서 엑셀 다운로드",
            description = "정산월 기간(from~to, 최대 maxSearchMonths개월)의 지급 완료(PAID) 정산서를 .xlsx로 내려준다. "
                    + "한 달만 받으려면 from과 to를 같은 달로. 시트 ① 정산서 ② 품목 내역, 각 시트 맨 아래 합계. "
                    + "누가·언제·어떤 조건으로 받았는지 로그로 남긴다")
    @ApiResponse(responseCode = "200", description = "엑셀 파일(application/vnd.openxmlformats-officedocument.spreadsheetml.sheet)")
    @ApiResponse(responseCode = "400", description = "400-SETTLEMENT_MONTH_RANGE_INVALID 기간 오류 / 400-SETTLEMENT_EXPORT_TOO_LARGE 행이 너무 많음 / 400-VALIDATION_FAILED 형식 오류")
    @GetMapping("/export")
    public ResponseEntity<Resource> exportSettlements(
            @AuthenticationPrincipal AuthMember authMember,
            @Parameter(description = "시작 정산월 yyyy-MM")
            @RequestParam @Pattern(regexp = SettlementPolicy.MONTH_PATTERN) String from,
            @Parameter(description = "끝 정산월 yyyy-MM (포함)")
            @RequestParam @Pattern(regexp = SettlementPolicy.MONTH_PATTERN) String to,
            @RequestParam(required = false) @Positive Long sellerId) {
        return SettlementExportResponses.of(settlementFacade.exportSettlements(authMember.memberId(),
                YearMonth.parse(from), YearMonth.parse(to), sellerId));
    }

    private static YearMonth monthOf(String value) {
        return value == null ? null : YearMonth.parse(value);
    }

    @Operation(summary = "정산서 상세", description = "금액, 묶인 후보, 최근 실패 사유, 시도 횟수, 다음 재시도 일시, 보류 사유")
    @ApiResponse(responseCode = "200", description = "200-1 조회 성공")
    @ApiResponse(responseCode = "404", description = "404-SETTLEMENT_NOT_FOUND")
    @GetMapping("/{settlementId}")
    public RsData<SettlementDetailResponse> getSettlement(@PathVariable Long settlementId) {
        return RsData.of("200-1", "정산서를 조회했습니다.", settlementFacade.getSettlement(settlementId));
    }

    @Operation(summary = "수동 재처리",
            description = "MANUAL_REQUIRED 정산서 1건을 자동 실행과 같은 지급 로직으로 다시 지급(사유 필수). 성공 PAID, 실패 MANUAL_REQUIRED 유지, 판매자가 제재 상태이거나 원장 불일치가 남았으면 지급하지 않고 HOLD(data.status·data.holdReason로 구분)")
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

    @Operation(summary = "정산 미리보기",
            description = "month(yyyy-MM) 월 정산을 지금 실행하면 집계될 판매자별 대상 후보 수·예상 금액(저장하지 않음). "
                    + "정산월이 대상월 이전인 READY 후보(이월분)도 포함하고, 제재된 판매자는 willHold=true")
    @ApiResponse(responseCode = "200", description = "200-1 조회 성공")
    @ApiResponse(responseCode = "400", description = "400-INVALID_REQUEST month 없음 / 400-VALIDATION_FAILED 형식 오류")
    @GetMapping("/preview")
    public RsData<SettlementPreviewResponse> getPreview(
            @RequestParam @NotBlank @Pattern(regexp = SettlementPolicy.MONTH_PATTERN) String month,
            @ParameterObject Pageable pageable) {
        return RsData.of("200-1", "정산 미리보기를 조회했습니다.",
                settlementFacade.getPreview(YearMonth.parse(month), pageable));
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
            description = "HOLD 정산서를 READY로 되돌린다. 지급은 10분마다 도는 재시도 스케줄러가 한다. 판매자가 제재 중이거나 원장 재대조 불일치가 풀리지 않았으면 해제할 수 없다")
    @ApiResponse(responseCode = "200", description = "200-1 해제 완료")
    @ApiResponse(responseCode = "404", description = "404-SETTLEMENT_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "409-SETTLEMENT_STATE_INVALID HOLD가 아님, 409-SETTLEMENT_SELLER_SANCTIONED 판매자 제재 중, 409-SETTLEMENT_LEDGER_MISMATCH_UNRESOLVED 원장 불일치 미해결")
    @PostMapping("/{settlementId}/release")
    public RsData<SettlementResponse> release(@PathVariable Long settlementId) {
        return RsData.of("200-1", "정산 보류를 해제했습니다.", settlementFacade.release(settlementId));
    }

    @Operation(summary = "원장 재대조 불일치 확인",
            description = "원장이 원래 값으로 돌아오지 않는 불일치(결제 쪽 정상 정정 등)를 관리자가 확인하고 지급을 더 막지 않기로 기록한다(사유 필수). "
                    + "처리 이력에 VERIFY·ADMIN·SUCCEEDED로 남고, 그 판매자에게 다른 불일치가 없으면 원장 불일치 사유로 보류된 정산서를 READY로 되돌린다. "
                    + "같은 불일치는 다음 재대조에서도 다시 막지 않고, 다른 불일치가 나오면 다시 막는다")
    @ApiResponse(responseCode = "200", description = "200-1 확인 완료 (data.releasedCount 자동 해제 수)")
    @ApiResponse(responseCode = "400", description = "400-VALIDATION_FAILED 사유 없음")
    @ApiResponse(responseCode = "404", description = "404-SETTLEMENT_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "409-SETTLEMENT_STATE_INVALID 풀리지 않은 원장 불일치가 없음")
    @PostMapping("/{settlementId}/ledger-mismatch/confirm")
    public RsData<SettlementLedgerMismatchConfirmResponse> confirmLedgerMismatch(
            @PathVariable Long settlementId, @Valid @RequestBody SettlementLedgerMismatchConfirmRequest request) {
        return RsData.of("200-1", "원장 불일치를 확인했습니다.",
                settlementFacade.confirmLedgerMismatch(settlementId, request.reason()));
    }

}