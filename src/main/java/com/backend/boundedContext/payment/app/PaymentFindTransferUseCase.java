package com.backend.boundedContext.payment.app;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.domain.WalletTransaction;
import com.backend.boundedContext.payment.out.WalletTransactionRepository;
import com.backend.shared.payment.dto.TransferResultDto;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentFindTransferUseCase {

    // 정산 지급 원장의 멱등 키 = 정산 멱등 키(SETTLEMENT-{id}) + 접미사
    static final String PAYOUT_KEY_SUFFIX = "-PAYOUT";
    static final String FEE_KEY_SUFFIX = "-FEE";

    private final WalletTransactionRepository walletTransactionRepository;

    // 정산 지급 내역 조회: 판매자 지급(PAYOUT)·수수료(FEE) 원장으로 결과를 다시 만든다. 둘 다 없으면 empty
    public Optional<TransferResultDto> findTransfer(String idempotencyKey) {
        Optional<WalletTransaction> payout = walletTransactionRepository.findByIdempotencyKey(idempotencyKey + PAYOUT_KEY_SUFFIX);
        Optional<WalletTransaction> fee = walletTransactionRepository.findByIdempotencyKey(idempotencyKey + FEE_KEY_SUFFIX);
        if (payout.isEmpty() && fee.isEmpty()) {
            return Optional.empty();
        }

        // 두 원장은 한 트랜잭션에서 저장되므로 지급 일시는 먼저 있는 쪽 기준
        WalletTransaction first = payout.orElseGet(fee::get);
        return Optional.of(new TransferResultDto(
                idempotencyKey,
                payout.map(WalletTransaction::getId).orElse(null),
                payout.map(WalletTransaction::getAmount).orElse(0L),
                fee.map(WalletTransaction::getAmount).orElse(0L),
                first.getCreatedAt()));
    }
}
