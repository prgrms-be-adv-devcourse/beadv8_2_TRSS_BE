package com.backend.boundedContext.payment.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.domain.Payment;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletHolding;
import com.backend.boundedContext.payment.domain.WalletTransaction;
import com.backend.boundedContext.payment.domain.WalletTransactionType;
import com.backend.boundedContext.payment.out.PaymentRepository;
import com.backend.boundedContext.payment.out.WalletHoldingRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.boundedContext.payment.out.WalletTransactionRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.payment.dto.PayCommand;
import com.backend.shared.payment.dto.PayResultDto;
import com.backend.shared.payment.dto.PayResultStatus;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentPayUseCase {

    private final WalletRepository walletRepository;
    private final PaymentRepository paymentRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final WalletHoldingRepository walletHoldingRepository;

    /**
     * 주문 결제: 구매자 지갑 → 홀딩
     * 1. 지갑을 먼저 잠근다. 잠그지 않고 잔액을 읽으면 동시 결제가 같은 잔액을 보고 둘 다 통과한다
     * 2. 잔액이 결제 금액 이상인지 확인
     * 3. 결제 건 저장 (원장이 결제 ID를 가리켜야 하므로 원장보다 먼저)
     * 4. 원장 PAYMENT 기록 (이때 지갑 잔액 차감)
     * 5. 주문의 홀딩 생성 (HELD)
     */
    public PayResultDto pay(PayCommand command) {
        Wallet wallet = walletRepository.findByMemberIdForUpdate(command.buyerId())
                .orElseThrow(() -> new DomainException(PaymentErrorCode.WALLET_NOT_FOUND));

        if (wallet.getBalance() < command.amount()) {
            // TODO 4단계: 잔액 부족은 예외가 아니라 FAILED_INSUFFICIENT_BALANCE 결과로 반환하고, 실패 기록은 별도 트랜잭션으로 저장
            throw new IllegalStateException("잔액이 부족합니다. balance=" + wallet.getBalance() + ", amount=" + command.amount());
        }

        LocalDateTime now = LocalDateTime.now();
        Payment payment = paymentRepository.save(
                Payment.paid(
                        command.orderId(),
                        wallet,
                        command.amount(),
                        command.idempotencyKey(),
                        now
                )
        );

        walletTransactionRepository.save(
                    WalletTransaction.record(
                            wallet,
                            WalletTransactionType.PAYMENT,
                            command.amount(),
                            payment.getId(),
                            payment.paymentIdempotencyKey()
                    )
        );
        walletHoldingRepository.save(
                WalletHolding.hold(
                        payment,
                        now
                )
        );

        return new PayResultDto(
                payment.getId(),
                PayResultStatus.PAID,
                0,
                0
        );
    }
}
