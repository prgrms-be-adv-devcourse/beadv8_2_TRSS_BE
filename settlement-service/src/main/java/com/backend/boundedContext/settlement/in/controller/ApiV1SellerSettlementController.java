package com.backend.boundedContext.settlement.in.controller;

import com.backend.boundedContext.settlement.app.SettlementFacade;
import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import com.backend.boundedContext.settlement.domain.SettlementSellerStatus;
import com.backend.boundedContext.settlement.in.dto.SellerSettlementDetailResponse;
import com.backend.boundedContext.settlement.in.dto.SellerSettlementResponse;
import com.backend.boundedContext.settlement.in.dto.SellerSettlementSummaryResponse;
import com.backend.boundedContext.settlement.in.dto.SettlementExpectedResponse;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;

/**
 * 판매자 본인 정산 조회 API (목록, 상세, 정산 예정 금액)
 *
 * /api/v1/seller/** 은 SecurityConfig에서 SELLER만 들어올 수 있다(아니면 403-FORBIDDEN)
 * 로그인 회원 ID로 판매자 ID를 찾아 본인 정산서만 보여 준다. 남의 정산서는 404
 * 실패·재처리 중인 정산서는 "지급 지연"으로만 보이고 내부 사유는 응답에 넣지 않는다
 */
@Tag(name = "판매자 정산", description = "판매자 본인 정산서·정산 예정 금액 조회 (SELLER)")
@RestController
@RequestMapping("/api/v1/seller/settlements")
@RequiredArgsConstructor
public class ApiV1SellerSettlementController {

    private final SettlementFacade settlementFacade;

    @Operation(summary = "본인 정산서 목록",
            description = "정산월·상태로 거를 수 있다(모두 선택), 최근 정산월부터. 실패·재처리 중인 정산서는 DELAYED(지급 지연)로만 표시")
    @ApiResponse(responseCode = "200", description = "200-1 조회 성공")
    @ApiResponse(responseCode = "400", description = "400-VALIDATION_FAILED 정산월 형식 오류 / 400-INVALID_REQUEST 상태 값 오류")
    @ApiResponse(responseCode = "403", description = "403-FORBIDDEN 판매자가 아님")
    @GetMapping
    public RsData<PageResponse<SellerSettlementResponse>> getSettlements(
            @AuthenticationPrincipal AuthMember authMember,
            @Parameter(description = "정산월 yyyy-MM")
            @RequestParam(required = false) @Pattern(regexp = SettlementPolicy.MONTH_PATTERN) String month,
            @Parameter(description = "기간 조회 시작 정산월 yyyy-MM (to와 함께, month와 같이 쓸 수 없음)")
            @RequestParam(required = false) @Pattern(regexp = SettlementPolicy.MONTH_PATTERN) String from,
            @Parameter(description = "기간 조회 끝 정산월 yyyy-MM (포함, 최대 maxSearchMonths개월)")
            @RequestParam(required = false) @Pattern(regexp = SettlementPolicy.MONTH_PATTERN) String to,
            @Parameter(description = "SCHEDULED(지급 예정) · PAID(지급 완료) · DELAYED(지급 지연) · ON_HOLD(지급 보류)")
            @RequestParam(required = false) SettlementSellerStatus status,
            @ParameterObject Pageable pageable) {
        return RsData.of("200-1", "정산서 목록을 조회했습니다.",
                settlementFacade.getSellerSettlements(authMember.memberId(), month, monthOf(from), monthOf(to),
                        status, pageable));
    }

    @Operation(summary = "정산 요약 카드",
            description = "조회 기간(from~to, 최대 maxSearchMonths개월)의 본인 지급 완료 건수·금액과 가장 최근 지급 정산서. "
                    + "다음 지급 예정액은 /expected")
    @ApiResponse(responseCode = "200", description = "200-1 조회 성공")
    @ApiResponse(responseCode = "400", description = "400-SETTLEMENT_MONTH_RANGE_INVALID 기간 오류 / 400-VALIDATION_FAILED 형식 오류")
    @ApiResponse(responseCode = "403", description = "403-FORBIDDEN 판매자가 아님")
    @GetMapping("/summary")
    public RsData<SellerSettlementSummaryResponse> getSummary(
            @AuthenticationPrincipal AuthMember authMember,
            @Parameter(description = "시작 정산월 yyyy-MM")
            @RequestParam @Pattern(regexp = SettlementPolicy.MONTH_PATTERN) String from,
            @Parameter(description = "끝 정산월 yyyy-MM (포함)")
            @RequestParam @Pattern(regexp = SettlementPolicy.MONTH_PATTERN) String to) {
        return RsData.of("200-1", "정산 요약을 조회했습니다.",
                settlementFacade.getSellerSummary(authMember.memberId(), YearMonth.parse(from), YearMonth.parse(to)));
    }

    @Operation(summary = "본인 지급 완료 정산서 엑셀 다운로드",
            description = "정산월 기간(from~to, 최대 maxSearchMonths개월)의 본인 지급 완료(PAID) 정산서를 .xlsx로 내려준다. "
                    + "월별 개별 다운로드는 from과 to를 같은 달로. 시트 ① 정산서 ② 품목 내역, 각 시트 맨 아래 합계")
    @ApiResponse(responseCode = "200", description = "엑셀 파일(application/vnd.openxmlformats-officedocument.spreadsheetml.sheet)")
    @ApiResponse(responseCode = "400", description = "400-SETTLEMENT_MONTH_RANGE_INVALID 기간 오류 / 400-SETTLEMENT_EXPORT_TOO_LARGE 행이 너무 많음 / 400-VALIDATION_FAILED 형식 오류")
    @ApiResponse(responseCode = "403", description = "403-FORBIDDEN 판매자가 아님")
    @GetMapping("/export")
    public ResponseEntity<Resource> exportSettlements(
            @AuthenticationPrincipal AuthMember authMember,
            @Parameter(description = "시작 정산월 yyyy-MM")
            @RequestParam @Pattern(regexp = SettlementPolicy.MONTH_PATTERN) String from,
            @Parameter(description = "끝 정산월 yyyy-MM (포함)")
            @RequestParam @Pattern(regexp = SettlementPolicy.MONTH_PATTERN) String to) {
        return SettlementExportResponses.of(settlementFacade.exportSellerSettlements(authMember.memberId(),
                YearMonth.parse(from), YearMonth.parse(to)));
    }

    private static YearMonth monthOf(String value) {
        return value == null ? null : YearMonth.parse(value);
    }

    @Operation(summary = "정산 예정 금액",
            description = "구매 확정됐지만 아직 정산서에 묶이지 않은 금액을 정산월별로(오래된 달부터), 예정 지급일과 함께")
    @ApiResponse(responseCode = "200", description = "200-1 조회 성공")
    @ApiResponse(responseCode = "403", description = "403-FORBIDDEN 판매자가 아님")
    @GetMapping("/expected")
    public RsData<SettlementExpectedResponse> getExpectedAmounts(@AuthenticationPrincipal AuthMember authMember) {
        return RsData.of("200-1", "정산 예정 금액을 조회했습니다.",
                settlementFacade.getExpectedAmounts(authMember.memberId()));
    }

    @Operation(summary = "본인 정산서 상세",
            description = "판매 대금·수수료·지급액과 품목별 내역. 이월된 품목은 원래 정산월(settlementMonth)과 carriedOver=true로 표시")
    @ApiResponse(responseCode = "200", description = "200-1 조회 성공")
    @ApiResponse(responseCode = "403", description = "403-FORBIDDEN 판매자가 아님")
    @ApiResponse(responseCode = "404", description = "404-SETTLEMENT_NOT_FOUND 없거나 남의 정산서")
    @GetMapping("/{settlementId}")
    public RsData<SellerSettlementDetailResponse> getSettlement(@AuthenticationPrincipal AuthMember authMember,
                                                                @PathVariable Long settlementId) {
        return RsData.of("200-1", "정산서를 조회했습니다.",
                settlementFacade.getSellerSettlement(authMember.memberId(), settlementId));
    }
}