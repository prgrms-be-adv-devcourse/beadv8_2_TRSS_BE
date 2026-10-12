package com.backend.boundedContext.payment.in;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backend.boundedContext.payment.app.PaymentFacade;
import com.backend.boundedContext.payment.app.dto.TopupPrepareDto;
import com.backend.boundedContext.payment.in.dto.TopupPrepareRequest;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "충전", description = "예치금 충전")
@RestController
@RequestMapping("/api/v1/topups")
@RequiredArgsConstructor
public class ApiV1TopupController {

    private final PaymentFacade paymentFacade;

    @Operation(summary = "충전 준비", description = "충전 금액을 저장하고 토스 주문번호(TOPUP-…)를 발급한다. 응답의 orderId·amount로 토스 결제창을 띄운다")
    @PostMapping
    public RsData<TopupPrepareDto> prepareTopup(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody TopupPrepareRequest request
    ) {
        return RsData.of("200-1", "충전이 준비되었습니다.", paymentFacade.prepareTopup(authMember.memberId(), request.amount()));
    }
}
