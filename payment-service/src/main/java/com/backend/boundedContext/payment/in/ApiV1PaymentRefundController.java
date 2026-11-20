package com.backend.boundedContext.payment.in;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backend.boundedContext.payment.app.PaymentFacade;
import com.backend.boundedContext.payment.app.dto.RefundDto;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "반품 환불", description = "반품 승인된 주문의 환불 조회, 단순 변심 반품 회수 택배비 결제 (구매자)")
@RestController
@RequestMapping("/api/v1/payments/refunds")
@RequiredArgsConstructor
public class ApiV1PaymentRefundController {

    private final PaymentFacade paymentFacade;

    @Operation(summary = "반품 환불 조회", description = """
            반품 승인된 주문의 환불 상태와 금액을 돌려준다.
            단순 변심이면 status가 PICKUP_FEE_REQUIRED일 때 회수 택배비(pickupFee)를 결제해야 환불(refundAmount, 상품값만)된다.
            그 밖의 사유는 결제 금액 전부를 바로 환불한다(pickupFee 0)""")
    @ApiResponse(responseCode = "200", description = "200-1 조회 성공")
    @ApiResponse(responseCode = "404", description = "404-REFUND_NOT_FOUND 환불 내역 없음(반품 승인 전이거나 본인 주문이 아님)")
    @GetMapping("/{orderId}")
    public RsData<RefundDto> getRefund(@AuthenticationPrincipal AuthMember authMember, @PathVariable Long orderId) {
        return RsData.of("200-1", "환불 내역을 조회했습니다.", paymentFacade.getRefund(authMember.memberId(), orderId));
    }

    @Operation(summary = "반품 회수 택배비 결제", description = """
            단순 변심 반품의 회수 택배비를 예치금으로 결제하고, 이어서 상품값을 환불한다(한 번에 처리).
            환불금과 상관없이 예치금에 회수 택배비만큼 있어야 한다. 이미 결제·환불했으면 같은 결과를 다시 돌려준다""")
    @ApiResponse(responseCode = "200", description = "200-1 결제·환불 완료 (status COMPLETED)")
    @ApiResponse(responseCode = "402", description = "402-INSUFFICIENT_BALANCE 예치금 부족 (data: shortage 모자란 금액, chargeAmount 충전 권장액)")
    @ApiResponse(responseCode = "404", description = "404-REFUND_NOT_FOUND 환불 내역 없음(반품 승인 전이거나 본인 주문이 아님)")
    @ApiResponse(responseCode = "409", description = "409-REFUND_PICKUP_FEE_NOT_REQUIRED 회수 택배비를 결제할 환불이 아님(단순 변심이 아니거나 처리 중·실패) / "
            + "409-REFUND_HOLDING_SETTLED 정산이 시작돼 환불할 수 없음")
    @PostMapping("/{orderId}/pickup-fee")
    public RsData<RefundDto> payPickupFee(@AuthenticationPrincipal AuthMember authMember, @PathVariable Long orderId) {
        return RsData.of("200-1", "반품 회수 택배비를 결제하고 환불했습니다.",
                paymentFacade.payReturnPickupFee(authMember.memberId(), orderId));
    }
}
