package com.backend.boundedContext.payment.in;

import java.util.Optional;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.app.PaymentFacade;
import com.backend.shared.payment.dto.PayCommand;
import com.backend.shared.payment.dto.PayResultDto;
import com.backend.shared.payment.dto.PayResultStatus;
import com.backend.shared.payment.dto.SettlementTransferCommand;
import com.backend.shared.payment.dto.TransferResultDto;
import com.backend.shared.payment.dto.WalletSummaryDto;
import com.backend.shared.payment.out.PaymentApi;

import lombok.RequiredArgsConstructor;

/**
 * {@link PaymentApi} 구현체
 *
 * TODO pay는 임시 구현(Stub), 실제 구현 시 PaymentFacade 호출로 바꾼다.
 * 주문 컨텍스트가 먼저 개발할 수 있도록 결제는 항상 성공으로 돌려주고, 지갑·홀딩·원장은 바꾸지 않는다.
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

    /** 임시 구현: 항상 결제 성공 (결제 건 ID 없음) */
    @Override
    public PayResultDto pay(PayCommand command) {
        return new PayResultDto(null, PayResultStatus.PAID, 0, 0);
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
