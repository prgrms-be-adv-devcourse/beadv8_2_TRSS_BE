package com.backend.boundedContext.payment.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.payment.dto.WalletSummaryDto;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentGetWalletUseCase {

    private final WalletRepository walletRepository;

    // 지갑 잔액 요약 (지갑이 없으면 404 WALLET_NOT_FOUND)
    public WalletSummaryDto getWallet(Long memberId) {
        Wallet wallet = walletRepository.findByMemberId(memberId)
                .orElseThrow(() -> new DomainException(PaymentErrorCode.WALLET_NOT_FOUND));

        // TODO 홀딩(wallet_holding) 구현 후 HELD 상태 홀딩 금액 합계로 교체
        long heldAmount = 0;
        return new WalletSummaryDto(memberId, wallet.getBalance(), heldAmount);
    }
}
