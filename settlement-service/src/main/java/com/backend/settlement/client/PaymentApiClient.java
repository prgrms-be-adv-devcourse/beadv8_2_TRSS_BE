package com.backend.settlement.client;

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
 * 정산이 쓰는 transfer(지급)는 돈이 움직이는 호출이라, 아무것도 안 한 채 성공을 돌려주면 안 되므로 예외를 던진다.
 * 그래서 연결 전에는 정산 스케줄러를 기본으로 꺼 둔다(application.yaml custom.scheduling.enabled).
 * 정산이 쓰지 않는 메서드도 UnsupportedOperationException.
 * 정산 코드는 SettlementPaymentPort → SettlementPaymentApiAdapter를 거쳐 이 구현을 부른다.
 * TODO 서비스 간 연결 작업에서 payment-service 내부 API(/internal/v1/payments/transfers)를 호출하도록 교체
 */
@Component
public class PaymentApiClient implements PaymentApi {

    // ── 정산이 쓰는 메서드: 연결 전이라 예외 ──

    @Override
    public TransferResultDto transfer(SettlementTransferCommand command) {
        throw new UnsupportedOperationException("payment-service 연결 전이라 정산 지급을 할 수 없습니다");
    }

    // ── 정산이 쓰지 않는 메서드 ──

    @Override
    public WalletSummaryDto getWallet(Long memberId) {
        throw notUsedInSettlement();
    }

    @Override
    public boolean hasPendingTopups(Long memberId) {
        throw notUsedInSettlement();
    }

    @Override
    public PayResultDto pay(PayCommand command) {
        throw notUsedInSettlement();
    }

    @Override
    public void cancelPayment(String idempotencyKey) {
        throw notUsedInSettlement();
    }

    @Override
    public List<PaymentAttemptDto> getPaymentAttempts(Long orderId) {
        throw notUsedInSettlement();
    }

    @Override
    public Optional<TransferResultDto> findTransfer(String idempotencyKey) {
        throw notUsedInSettlement();
    }

    private UnsupportedOperationException notUsedInSettlement() {
        return new UnsupportedOperationException("settlement-service에서 쓰지 않는 PaymentApi 메서드");
    }
}
