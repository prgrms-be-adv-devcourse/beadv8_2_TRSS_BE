package com.backend.boundedContext.payment.in;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backend.boundedContext.payment.app.PaymentFacade;
import com.backend.global.rsData.RsData;
import com.backend.shared.payment.dto.PayCommand;
import com.backend.shared.payment.dto.PayResultDto;
import com.backend.shared.payment.dto.PaymentAttemptDto;
import com.backend.shared.payment.dto.SettlementTransferCommand;
import com.backend.shared.payment.dto.TransferResultDto;
import com.backend.shared.payment.dto.WalletSummaryDto;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;

/**
 * 결제 컨텍스트 내부 API. {@link com.backend.shared.payment.out.PaymentApi}를 HTTP로 제공한다.
 * 결제 모듈을 분리하면 주문·정산·회원 쪽이 PaymentApi의 HTTP 어댑터로 이 API를 부른다.
 * 사용자용 API(/api/v1/**)가 아니라 서비스끼리만 부르는 API라 /internal 아래에 두고 Swagger에서 숨긴다.
 * 응답은 다른 API와 같은 RsData 형식이고, 실패는 GlobalExceptionHandler가 같은 resultCode(예: 409-PAYMENT_ALREADY_PAID)로 돌려준다.
 */
@Hidden
@RestController
@RequestMapping("/internal/v1/payments")
@RequiredArgsConstructor
public class InternalPaymentController {

    private final PaymentFacade paymentFacade;

    // PaymentApi.getWallet
    @GetMapping("/wallets/{memberId}")
    public RsData<WalletSummaryDto> getWallet(@PathVariable Long memberId) {
        return RsData.of(
                "200-1",
                "지갑을 조회했습니다.",
                paymentFacade.getWallet(memberId)
        );
    }

    // PaymentApi.hasPendingTopups
    @GetMapping("/wallets/{memberId}/pending-topups")
    public RsData<Boolean> hasPendingTopups(@PathVariable Long memberId) {
        return RsData.of(
                "200-1",
                "결과가 정해지지 않은 충전 건이 있는지 조회했습니다.",
                paymentFacade.hasPendingTopups(memberId)
        );
    }

    // PaymentApi.pay. 잔액 부족·주문 만료도 실패 결과(FAILED_*)로 200 응답한다
    @PostMapping("/pay")
    public RsData<PayResultDto> pay(@RequestBody PayCommand command) {
        return RsData.of(
                "200-1",
                "결제를 처리했습니다.",
                paymentFacade.pay(command)
        );
    }

    // PaymentApi.cancelPayment. 결제가 없거나 이미 취소됐어도 200 (여러 번 불러도 한 번만 취소)
    @PostMapping("/{idempotencyKey}/cancel")
    public RsData<Void> cancelPayment(@PathVariable String idempotencyKey) {
        paymentFacade.cancelPayment(idempotencyKey);
        return RsData.of("200-1", "결제 취소를 처리했습니다.");
    }

    // PaymentApi.getPaymentAttempts
    @GetMapping("/orders/{orderId}/attempts")
    public RsData<List<PaymentAttemptDto>> getPaymentAttempts(@PathVariable Long orderId) {
        return RsData.of(
                "200-1",
                "결제 시도 목록을 조회했습니다.",
                paymentFacade.getPaymentAttempts(orderId)
        );
    }

    // PaymentApi.transfer
    @PostMapping("/transfers")
    public RsData<TransferResultDto> transfer(@RequestBody SettlementTransferCommand command) {
        return RsData.of(
                "200-1",
                "정산 지급을 처리했습니다.",
                paymentFacade.transfer(command)
        );
    }

    // PaymentApi.findTransfer. 지급 내역이 없으면 data가 null (HTTP 어댑터는 Optional.empty로 바꾼다)
    @GetMapping("/transfers/{idempotencyKey}")
    public RsData<TransferResultDto> findTransfer(@PathVariable String idempotencyKey) {
        return RsData.of(
                "200-1",
                "정산 지급 내역을 조회했습니다.",
                paymentFacade.findTransfer(idempotencyKey).orElse(null)
        );
    }
}
