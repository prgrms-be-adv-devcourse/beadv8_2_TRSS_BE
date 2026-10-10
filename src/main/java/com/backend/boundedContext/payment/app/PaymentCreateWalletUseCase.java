package com.backend.boundedContext.payment.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.out.WalletRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentCreateWalletUseCase {

    private final WalletRepository walletRepository;

    // 잔액 0인 지갑 생성 (이미 있으면 무시)
    public void createWallet(Long memberId) {
        if (walletRepository.existsByMemberId(memberId)) {
            return;
        }
        walletRepository.save(Wallet.create(memberId));
    }
}
