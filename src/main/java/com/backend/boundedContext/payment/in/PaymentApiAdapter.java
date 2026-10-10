package com.backend.boundedContext.payment.in;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.app.PaymentFacade;
import com.backend.shared.payment.out.PaymentApi;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentApiAdapter implements PaymentApi {

    @Lazy   // 컨텍스트 간 순환 참조 방지
    private final PaymentFacade paymentFacade;

    @Override
    public void createWallet(Long memberId) {
        paymentFacade.createWallet(memberId);
    }
}
