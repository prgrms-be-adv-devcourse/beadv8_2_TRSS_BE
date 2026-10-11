package com.backend.boundedContext.payment.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.shared.payment.dto.WalletSummaryDto;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentFacade {

    private final PaymentCreateWalletUseCase paymentCreateWalletUseCase;
    private final PaymentGetWalletUseCase paymentGetWalletUseCase;

    // 지갑 생성 (진행 중인 트랜잭션이 있으면 참여, 없으면 새로 시작)
    @Transactional
    public void createWallet(Long memberId) {
        paymentCreateWalletUseCase.createWallet(memberId);
    }

    // 지갑 잔액 요약
    @Transactional(readOnly = true)
    public WalletSummaryDto getWallet(Long memberId) {
        return paymentGetWalletUseCase.getWallet(memberId);
    }
}
