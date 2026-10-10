package com.backend.boundedContext.payment.in;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.app.PaymentFacade;
import com.backend.shared.member.event.MemberSignedUpEvent;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private final PaymentFacade paymentFacade;

    // 회원 가입 시 예치금 지갑 생성 (지금은 가입 트랜잭션 안에서 실행되어 실패하면 가입도 롤백)
    @EventListener
    public void handle(MemberSignedUpEvent event) {
        paymentFacade.createWallet(event.memberId());
    }
}
