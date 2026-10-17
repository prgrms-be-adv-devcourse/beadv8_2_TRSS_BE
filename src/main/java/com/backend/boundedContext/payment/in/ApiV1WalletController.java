package com.backend.boundedContext.payment.in;

import java.time.LocalDate;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backend.boundedContext.payment.app.PaymentFacade;
import com.backend.boundedContext.payment.app.dto.WalletTransactionDto;
import com.backend.boundedContext.payment.domain.WalletTransactionType;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import com.backend.shared.payment.dto.WalletSummaryDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "지갑", description = "예치금 지갑")
@RestController
@RequestMapping("/api/v1/wallet")
@RequiredArgsConstructor
public class ApiV1WalletController {

    private final PaymentFacade paymentFacade;

    @Operation(summary = "지갑 조회", description = "사용 가능 금액(balance)과 결제 후 판매자에게 지급되기 전인 홀딩 중 금액(heldAmount)을 돌려준다")
    @GetMapping
    public RsData<WalletSummaryDto> getWallet(@AuthenticationPrincipal AuthMember authMember) {
        return RsData.of("200-1", "지갑을 조회했습니다.", paymentFacade.getWallet(authMember.memberId()));
    }

    @Operation(summary = "내 예치금 거래 내역", description = """
            잔액이 바뀐 거래(원장)를 최신순으로 돌려준다. amount는 입금이면 +, 차감이면 -다.
            유형(type)·기간(from~to, 양 끝 날짜 포함)은 선택이다. page(0부터)·size(기본 20, 최대 100), 정렬은 고정이라 sort는 무시한다""")
    @ApiResponse(responseCode = "200", description = "200-1 조회 성공")
    @ApiResponse(responseCode = "400", description = "400-PAYMENT_PERIOD_INVALID 시작일이 종료일보다 늦음")
    @ApiResponse(responseCode = "404", description = "404-WALLET_NOT_FOUND 지갑 없음")
    @GetMapping("/transactions")
    public RsData<PageResponse<WalletTransactionDto>> getTransactions(
            @AuthenticationPrincipal AuthMember authMember,
            @Parameter(description = "거래 유형 (CHARGE, CHARGE_CANCEL, PAYMENT, REFUND, SETTLEMENT, FEE)")
            @RequestParam(required = false) WalletTransactionType type,
            @Parameter(description = "조회 시작일 (yyyy-MM-dd)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일 (yyyy-MM-dd)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "거래 내역을 조회했습니다.",
                paymentFacade.getWalletTransactions(authMember.memberId(), type, from, to, pageable));
    }
}
