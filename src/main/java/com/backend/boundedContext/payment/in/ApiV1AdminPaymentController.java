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
}
