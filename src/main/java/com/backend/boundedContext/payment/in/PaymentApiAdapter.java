package com.backend.boundedContext.payment.in;

import java.util.Optional;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.app.PaymentFacade;
import com.backend.shared.payment.dto.PayCommand;
import com.backend.shared.payment.dto.PayResultDto;
import com.backend.shared.payment.dto.SettlementTransferCommand;
import com.backend.shared.payment.dto.TransferResultDto;
import com.backend.shared.payment.dto.WalletSummaryDto;
import com.backend.shared.payment.out.PaymentApi;

import lombok.RequiredArgsConstructor;

/**
 * {@link PaymentApi} 구현체
 */
@Component
@RequiredArgsConstructor
public class PaymentApiAdapter implements PaymentApi {

    @Lazy   // 컨텍스트 간 순환 참조 방지
    private final PaymentFacade paymentFacade;

    @Override
    public WalletSummaryDto getWallet(Long memberId) {
        return paymentFacade.getWallet(memberId);
    }

    @Override
    public PayResultDto pay(PayCommand command) {
        return paymentFacade.pay(command);
    }

    @Override
    public TransferResultDto transfer(SettlementTransferCommand command) {
        return paymentFacade.transfer(command);
    }

    @Override
    public Optional<TransferResultDto> findTransfer(String idempotencyKey) {
        return paymentFacade.findTransfer(idempotencyKey);
    }
}
