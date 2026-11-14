package com.backend.boundedContext.settlement.out;

import org.springframework.stereotype.Component;

import com.backend.shared.payment.dto.SettlementTransferCommand;
import com.backend.shared.payment.dto.TransferResultDto;
import com.backend.shared.payment.out.PaymentApi;

import lombok.RequiredArgsConstructor;

import java.util.Optional;

/**
 * 같은 앱의 PaymentApi로 SettlementPaymentPort를 구현함 (정산 모듈 분리 전까지 사용)
 * PaymentApi.transfer는 HTTP라 정산 트랜잭션에 참여하지 않는다. 그래서 SettlementPayoutUseCase가 지급을
 * ① PROCESSING 선점 → ② 트랜잭션 밖 transfer → ③ 결과 기록으로 나누고, 결과를 모르면 findTransfer로 확정한다
 */
@Component
@RequiredArgsConstructor
public class SettlementPaymentApiAdapter implements SettlementPaymentPort {

    private final PaymentApi paymentApi;

    @Override
    public TransferResultDto transfer(SettlementTransferCommand command) {
        return paymentApi.transfer(command);
    }

    /** payment-service 연결 전에는 PaymentApiClient가 예외를 던진다(원장 재대조는 설정으로 꺼 둠) */
    @Override
    public Optional<TransferResultDto> findTransfer(String idempotencyKey) {
        return paymentApi.findTransfer(idempotencyKey);
    }
}
