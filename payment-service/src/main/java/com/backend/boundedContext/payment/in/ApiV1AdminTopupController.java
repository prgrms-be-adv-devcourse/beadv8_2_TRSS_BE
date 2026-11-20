package com.backend.boundedContext.payment.in;

import java.time.LocalDate;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backend.boundedContext.payment.app.PaymentFacade;
import com.backend.boundedContext.payment.app.dto.AdminTopupDto;
import com.backend.boundedContext.payment.app.dto.AdminTopupManualResolveDto;
import com.backend.boundedContext.payment.app.dto.AdminTopupPgPaymentDto;
import com.backend.boundedContext.payment.in.dto.TopupManualResolveRequest;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import com.backend.shared.admin.dto.AdminFailureItem;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "관리자 충전", description = "관리자 결제 조회 충전 탭, 실패 거래, 충전 PG 결제 조회·수동 확인 정리")
@RestController
@RequestMapping("/api/v1/admin/topups")
@RequiredArgsConstructor
public class ApiV1AdminTopupController {

    private final PaymentFacade paymentFacade;

    @Operation(summary = "충전 탭 조회", description = """
            성공·실패를 포함한 전체 회원의 충전 건을 최신순으로 돌려준다(sort 무시). page(0부터)·size(기본 20, 최대 100).
            검색어(keyword)가 회원 이메일(@ 포함)이면 정확히 일치하는 회원의 충전 건, 충전번호(TOPUP-…)면 그 번호로 시작하는 충전 건이고, 그 외는 0건이다.
            충전번호는 앞부분만 넣어도 찾는다(화면에 보이는 앞 13자나 전체 번호). TOPUP- 뒤 4자리 이상(총 10자 이상)이어야 하고, 그보다 짧으면 0건이다. 대소문자를 구분한다.
            기간(from~to)은 충전 준비일 기준이고 양 끝 날짜를 포함한다""")
    @ApiResponse(responseCode = "200", description = "200-1 조회 성공")
    @ApiResponse(responseCode = "400", description = "400-PAYMENT_PERIOD_INVALID 시작일이 종료일보다 늦음")
    @ApiResponse(responseCode = "403", description = "관리자가 아님")
    @GetMapping
    public RsData<PageResponse<AdminTopupDto>> getTopups(
            @Parameter(description = "검색어: 회원 이메일(정확히 일치) 또는 충전번호(앞부분 일치, 10자 이상)")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "조회 시작일 (yyyy-MM-dd)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일 (yyyy-MM-dd)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "충전 내역을 조회했습니다.", paymentFacade.getAdminTopups(keyword, from, to, pageable));
    }

    @Operation(summary = "실패 거래: 충전 확인 지연", description = """
            관리자 실패 거래 화면의 충전 행. 공통 형식(AdminFailureItem, type TOPUP_DELAYED), 지금 상태가 된 시각(occurredAt) 최신순(sort 무시).
            기간(from~to, yyyy-MM-dd)은 그 시각의 날짜 기준이고 양 끝 날짜를 포함한다. page(0부터)·size
            - 결과 확인 대기: PROCESSING·CANCELING·METHOD_CANCELING으로 5분(결과 확인 대기 시간)보다 오래 남은 건.
              60분(오래 남은 건 경고 기준) 이하는 AUTO_RECHECK(자동 확인 대기), 넘으면 CHECK_REQUIRED(확인 필요)
            - 실패: FAILED_SYSTEM_ERROR·FAILED_AMOUNT_MISMATCH, CHECK_REQUIRED. 원인 문구에 실패 사유를 붙인다
            사용자 취소·만료·카드 거절·결제 수단 자동 취소는 정상 흐름이라 나오지 않는다. orderId는 null""")
    @ApiResponse(responseCode = "200", description = "200-1 조회 성공")
    @ApiResponse(responseCode = "403", description = "관리자가 아님")
    @GetMapping("/failures")
    public RsData<PageResponse<AdminFailureItem>> getFailures(
            @Parameter(description = "시작 날짜 yyyy-MM-dd (포함)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "끝 날짜 yyyy-MM-dd (포함)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "실패 거래(충전 확인 지연)를 조회했습니다.", paymentFacade.getAdminTopupFailures(from, to, pageable));
    }

    @Operation(summary = "충전 PG 결제 조회", description = """
            충전 건의 우리 기록(상태·남은 금액·취소 중 금액·취소된 금액·수동 확인 사유)과 그 자리에서 결제한 PG에 조회한 결과(상태·취소 합계)를 함께 돌려준다.
            수동 확인 건을 정리하기 전에 PG 취소 합계를 비교하는 용도. PG를 조회하지 못하면 pg.result가 UNKNOWN이고 message에 원인이 있다""")
    @ApiResponse(responseCode = "200", description = "200-1 조회 성공")
    @ApiResponse(responseCode = "403", description = "관리자가 아님")
    @ApiResponse(responseCode = "404", description = "404-TOPUP_NOT_FOUND 충전 건이 없음")
    @GetMapping("/{topupId}/pg-payment")
    public RsData<AdminTopupPgPaymentDto> getPgPayment(@PathVariable Long topupId) {
        return RsData.of("200-1", "충전 PG 결제를 조회했습니다.", paymentFacade.getAdminTopupPgPayment(topupId));
    }

    @Operation(summary = "충전 수동 확인 정리", description = """
            대사가 자동으로 정할 수 없어 수동 확인으로 표시한 충전 건을 관리자가 고른 방법으로 정리한다. 충전 PG 결제 조회로 PG 취소 합계를 확인한 뒤 고른다.
            - CANCELING: CONFIRM_CANCEL(이번 취소가 PG에서 처리됨 → 취소 확정), RESTORE(처리되지 않음 → 되돌림, 잔액·남은 금액 복원)
            - METHOD_CANCELING: CONFIRM_METHOD_CANCEL(PG에서 전액 취소됨 → 확정), RETRY_METHOD_CANCEL(PG 전액 취소 다시 요청. 끝나지 않으면 METHOD_CANCELING으로 남고 대사가 다시 확인)
            정리하면 수동 확인 표시가 지워진다""")
    @ApiResponse(responseCode = "200", description = "200-1 정리 성공")
    @ApiResponse(responseCode = "400", description = "action이 없거나 알 수 없는 값")
    @ApiResponse(responseCode = "403", description = "관리자가 아님")
    @ApiResponse(responseCode = "404", description = "404-TOPUP_NOT_FOUND 충전 건이 없음")
    @ApiResponse(responseCode = "409", description = "409-TOPUP_NOT_MANUAL_CHECK 수동 확인 건이 아님 / 409-TOPUP_MANUAL_RESOLVE_NOT_ALLOWED 지금 상태에서 고를 수 없는 정리")
    @PostMapping("/{topupId}/manual-resolve")
    public RsData<AdminTopupManualResolveDto> resolveManualCheck(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long topupId,
            @Valid @RequestBody TopupManualResolveRequest request
    ) {
        return RsData.of("200-1", "충전 수동 확인 건을 정리했습니다.", paymentFacade.resolveTopupManualCheck(authMember.memberId(), topupId, request.action()));
    }
}
