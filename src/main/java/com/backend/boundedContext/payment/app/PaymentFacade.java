package com.backend.boundedContext.payment.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentFacade {

    private final PaymentCreateWalletUseCase paymentCreateWalletUseCase;

    // 지갑 생성 (진행 중인 트랜잭션이 있으면 참여, 없으면 새로 시작)
    @Transactional
    public void createWallet(Long memberId) {
        paymentCreateWalletUseCase.createWallet(memberId);
    }
}
