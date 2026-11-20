package com.backend.boundedContext.payment.in;

import java.time.LocalDate;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backend.boundedContext.payment.app.PaymentFacade;
import com.backend.boundedContext.payment.app.dto.AdminPaymentDto;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import com.backend.shared.admin.dto.AdminFailureItem;
import com.backend.shared.admin.dto.AdminFailureType;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "관리자 결제", description = "결제 처리 상태 조회 (조회 전용)")
@RestController
@RequestMapping("/api/v1/admin/payments")
@RequiredArgsConstructor
public class ApiV1AdminPaymentController {

    private final PaymentFacade paymentFacade;

    @Operation(summary = "결제 탭 조회", description = """
            성공·실패를 포함한 결제 시도를 최신순으로 돌려준다(sort 무시). page(0부터)·size(기본 20, 최대 100).
            검색어(keyword)는 @가 있으면 회원 이메일, 없으면 주문번호로 보고 정확히 일치하는 결제를 찾는다.
            기간(from~to)은 결제 시도일 기준이고 양 끝 날짜를 포함한다""")
    @ApiResponse(responseCode = "200", description = "200-1 조회 성공")
    @ApiResponse(responseCode = "400", description = "400-PAYMENT_PERIOD_INVALID 시작일이 종료일보다 늦음")
    @ApiResponse(responseCode = "403", description = "관리자가 아님")
    @GetMapping
    public RsData<PageResponse<AdminPaymentDto>> getPayments(
            @Parameter(description = "검색어: 회원 이메일 또는 주문번호 (정확히 일치)")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "조회 시작일 (yyyy-MM-dd)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일 (yyyy-MM-dd)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "결제 내역을 조회했습니다.", paymentFacade.getAdminPayments(keyword, from, to, pageable));
    }

    @Operation(summary = "실패 거래: 결제 실패, 환불 실패", description = """
            관리자 실패 거래 화면(A7)의 결제, 환불 행. 공통 형식(AdminFailureItem), 지금 상태가 된 시각(occurredAt) 최신순.
            기간(from~to, yyyy-MM-dd)은 그 시각의 날짜 기준이고 양 끝 날짜를 포함한다. page(0부터)·size
            - PAYMENT_FAILED: 결제 실패(잔액 부족, 주문 만료, 시스템 오류)
            - REFUND_FAILED: 반품 환불 실패(FAILED_*). 시스템 오류이고 재시도가 남았으면 자동 재시도 대기, 그 밖에는 수동 확인 필요
            type이 없으면 둘을 합쳐 최신순. 이 API가 다루지 않는 type이면 빈 목록""")
    @ApiResponse(responseCode = "200", description = "200-1 조회 성공")
    @ApiResponse(responseCode = "403", description = "관리자가 아님")
    @GetMapping("/failures")
    public RsData<PageResponse<AdminFailureItem>> getFailures(
            @Parameter(description = "PAYMENT_FAILED 또는 REFUND_FAILED. 없으면 둘 다")
            @RequestParam(required = false) AdminFailureType type,
            @Parameter(description = "시작 날짜 yyyy-MM-dd (포함)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "끝 날짜 yyyy-MM-dd (포함)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "실패 거래(결제, 환불)를 조회했습니다.",
                paymentFacade.getAdminFailures(type, from, to, pageable));
    }
}
