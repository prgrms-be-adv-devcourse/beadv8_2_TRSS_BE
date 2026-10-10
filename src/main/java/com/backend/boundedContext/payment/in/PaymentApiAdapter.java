package com.backend.boundedContext.payment.in;

import org.springframework.stereotype.Component;

import com.backend.shared.payment.out.PaymentApi;

import lombok.extern.slf4j.Slf4j;

// TODO 결제 컨텍스트 구현 전 임시 구현. 지갑 생성이 구현되면 paymentFacade.createWallet(memberId) 호출로 바꾼다.
@Slf4j
@Component
public class PaymentApiAdapter implements PaymentApi {

    @Override
    public void createWallet(Long memberId) {
        log.warn("[임시] 지갑 생성 미구현: memberId={}", memberId);
    }
}
