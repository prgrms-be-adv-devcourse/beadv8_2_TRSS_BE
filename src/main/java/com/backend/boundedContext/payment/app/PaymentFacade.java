package com.backend.boundedContext.payment.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.boundedContext.payment.app.dto.TopupConfirmDto;
import com.backend.boundedContext.payment.app.dto.TopupPrepareDto;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentFacade {

    private final PaymentCreateWalletUseCase paymentCreateWalletUseCase;
    private final PaymentPrepareTopupUseCase paymentPrepareTopupUseCase;
    private final PaymentConfirmTopupUseCase paymentConfirmTopupUseCase;

    // 지갑 생성 (진행 중인 트랜잭션이 있으면 참여, 없으면 새로 시작)
    @Transactional
    public void createWallet(Long memberId) {
        paymentCreateWalletUseCase.createWallet(memberId);
    }

    // 충전 준비
    @Transactional
    public TopupPrepareDto prepareTopup(Long memberId, long amount) {
        return paymentPrepareTopupUseCase.prepareTopup(memberId, amount);
    }

    // 충전 승인. 토스 호출을 DB 트랜잭션 밖에서 해야 하므로 @Transactional을 붙이지 않음 (트랜잭션은 PaymentTopupConfirmProcessor의 Tx1·Tx2에서 각각 실행)
    public TopupConfirmDto confirmTopup(Long memberId, String paymentKey, String orderId, long amount) {
        return paymentConfirmTopupUseCase.confirmTopup(memberId, paymentKey, orderId, amount);
    }
}
