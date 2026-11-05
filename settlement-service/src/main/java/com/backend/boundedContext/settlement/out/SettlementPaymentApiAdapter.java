package com.backend.boundedContext.settlement.out;

import org.springframework.stereotype.Component;

import com.backend.shared.payment.dto.SettlementTransferCommand;
import com.backend.shared.payment.dto.TransferResultDto;
import com.backend.shared.payment.out.PaymentApi;

import lombok.RequiredArgsConstructor;

/**
 * 같은 앱의 PaymentApi로 SettlementPaymentPort를 구현함 (정산 모듈 분리 전까지 사용)
 * PaymentApi.transfer는 호출한 쪽 트랜잭션에 참여하므로, 지금은 정산서 PAID와 지급이 한 트랜잭션으로 묶임
 */
@Component
@RequiredArgsConstructor
public class SettlementPaymentApiAdapter implements SettlementPaymentPort {

    private final PaymentApi paymentApi;

    @Override
    public TransferResultDto transfer(SettlementTransferCommand command) {
        return paymentApi.transfer(command);
    }
}
