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

    // 회원 가입 시 예치금 지갑 생성. 가입 커밋 후 outbox 릴레이가 자기 트랜잭션 안에서 전달한다
    // 실패하면 릴레이 트랜잭션이 롤백되고 다음 회차에 재시도 (가입은 이미 커밋돼 영향 없음). 이미 지갑이 있으면 건너뜀
    @EventListener
    public void handle(MemberSignedUpEvent event) {
        paymentFacade.createWallet(event.memberId());
    }
}
