package com.backend.market.client;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.backend.shared.payment.dto.PayCommand;
import com.backend.shared.payment.dto.PayResultDto;
import com.backend.shared.payment.dto.PaymentAttemptDto;
import com.backend.shared.payment.dto.SettlementTransferCommand;
import com.backend.shared.payment.dto.TransferResultDto;
import com.backend.shared.payment.dto.WalletSummaryDto;
import com.backend.shared.payment.out.PaymentApi;

/**
 * payment-service 연결 전 임시 구현.
 * 결제(pay)는 성공한 척하면 돈이 움직이지 않은 주문이 PAID가 되므로 예외를 던진다.
 * 결제 내역 조회는 빈 목록을 돌려준다. market이 쓰지 않는 메서드는 UnsupportedOperationException.
 * TODO 서비스 간 연결 작업에서 payment-service 내부 API를 호출하도록 교체.
 *      pay는 지금 주문 트랜잭션에 참여하는 설계라, HTTP로 바꿀 때 실패·재시도 처리를 주문·결제 담당이 같이 정한다
 */
@Component
public class PaymentApiClient implements PaymentApi {

    // ── market이 쓰는 메서드 ──

    @Override
    public PayResultDto pay(PayCommand command) {
        throw new UnsupportedOperationException("payment-service 연결 전이라 결제할 수 없습니다");
    }

    // 주문 쪽 보상(결제 후 주문 처리 실패)용. 결제가 막혀 있어 취소할 결제도 없지만, 조용히 넘어가지 않게 예외를 던진다
    @Override
    public void cancelPayment(String idempotencyKey) {
        throw new UnsupportedOperationException("payment-service 연결 전이라 결제를 취소할 수 없습니다");
    }

    @Override
    public List<PaymentAttemptDto> getPaymentAttempts(Long orderId) {
        return List.of();
    }

    // ── market이 쓰지 않는 메서드 ──

    @Override
    public WalletSummaryDto getWallet(Long memberId) {
        throw notUsedInMarket();
    }

    @Override
    public boolean hasPendingTopups(Long memberId) {
        throw notUsedInMarket();
    }

    @Override
    public TransferResultDto transfer(SettlementTransferCommand command) {
        throw notUsedInMarket();
    }

    @Override
    public Optional<TransferResultDto> findTransfer(String idempotencyKey) {
        throw notUsedInMarket();
    }

    private UnsupportedOperationException notUsedInMarket() {
        return new UnsupportedOperationException("market-service에서 쓰지 않는 PaymentApi 메서드");
    }
}
