package com.backend.boundedContext.payment.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletHoldingStatus;
import com.backend.boundedContext.payment.out.WalletHoldingRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.payment.dto.WalletSummaryDto;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentGetWalletUseCase {

    private final WalletRepository walletRepository;
    private final WalletHoldingRepository walletHoldingRepository;

    // 지갑 잔액 요약 (지갑이 없으면 404 WALLET_NOT_FOUND)
    // 홀딩 중 금액 = 결제했지만 아직 판매자에게 지급되지 않은 금액 (HELD 홀딩의 남은 금액 합계)
    public WalletSummaryDto getWallet(Long memberId) {
        Wallet wallet = walletRepository.findByMemberId(memberId)
                .orElseThrow(() -> new DomainException(PaymentErrorCode.WALLET_NOT_FOUND));

        long heldAmount = walletHoldingRepository.sumRemainingAmount(wallet.getId(), WalletHoldingStatus.HELD);
        return new WalletSummaryDto(memberId, wallet.getBalance(), heldAmount);
    }
}
