package com.backend.boundedContext.payment.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentFacade {

    private final PaymentCreateWalletUseCase paymentCreateWalletUseCase;

    // 지갑 생성 (호출한 쪽 트랜잭션에 참여)
    @Transactional
    public void createWallet(Long memberId) {
        paymentCreateWalletUseCase.createWallet(memberId);
    }
}
