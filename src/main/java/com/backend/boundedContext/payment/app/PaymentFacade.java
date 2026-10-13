package com.backend.boundedContext.payment.app;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.boundedContext.payment.app.dto.TopupPrepareDto;
import com.backend.shared.payment.dto.SettlementTransferCommand;
import com.backend.shared.payment.dto.TransferResultDto;
import com.backend.shared.payment.dto.WalletSummaryDto;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentFacade {

    private final PaymentCreateWalletUseCase paymentCreateWalletUseCase;
    private final PaymentGetWalletUseCase paymentGetWalletUseCase;
    private final PaymentPrepareTopupUseCase paymentPrepareTopupUseCase;
    private final PaymentTransferUseCase paymentTransferUseCase;
    private final PaymentFindTransferUseCase paymentFindTransferUseCase;

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

    // 충전 준비
    @Transactional
    public TopupPrepareDto prepareTopup(Long memberId, long amount) {
        return paymentPrepareTopupUseCase.prepareTopup(memberId, amount);
    }

    // 정산 지급 (정산 지급 트랜잭션에 참여, 실패하면 함께 롤백)
    @Transactional
    public TransferResultDto transfer(SettlementTransferCommand command) {
        return paymentTransferUseCase.transfer(command);
    }

    // 정산 지급 내역 조회
    @Transactional(readOnly = true)
    public Optional<TransferResultDto> findTransfer(String idempotencyKey) {
        return paymentFindTransferUseCase.findTransfer(idempotencyKey);
    }
}
