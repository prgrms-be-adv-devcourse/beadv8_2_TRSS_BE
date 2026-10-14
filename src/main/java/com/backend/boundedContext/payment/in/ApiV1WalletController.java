package com.backend.boundedContext.payment.in;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backend.boundedContext.payment.app.PaymentFacade;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import com.backend.shared.payment.dto.WalletSummaryDto;

import io.swagger.v3.oas.annotations.Operation;
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
}
