package com.backend.boundedContext.payment.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.app.dto.TopupPrepareDto;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.PaymentPolicy;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentPrepareTopupUseCase {

    private final PaymentPolicy paymentPolicy;
    private final WalletRepository walletRepository;
    private final TopupRepository topupRepository;

    // 충전 준비: 금액 검증 후 토스 주문번호를 발급해 READY로 저장
    public TopupPrepareDto prepareTopup(Long memberId, long amount) {
        paymentPolicy.validateTopupAmount(amount);

        Wallet wallet = walletRepository.findByMemberId(memberId)
                .orElseThrow(() -> new DomainException(PaymentErrorCode.WALLET_NOT_FOUND));

        Topup topup = topupRepository.save(Topup.create(memberId, wallet, amount));

        return TopupPrepareDto.from(topup);
    }
}
