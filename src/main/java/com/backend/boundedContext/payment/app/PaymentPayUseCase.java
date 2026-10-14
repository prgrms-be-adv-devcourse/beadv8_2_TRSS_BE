package com.backend.boundedContext.payment.app;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.domain.Payment;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.PaymentStatus;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletHolding;
import com.backend.boundedContext.payment.domain.WalletTransaction;
import com.backend.boundedContext.payment.domain.WalletTransactionType;
import com.backend.boundedContext.payment.out.PaymentRepository;
import com.backend.boundedContext.payment.out.WalletHoldingRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.boundedContext.payment.out.WalletTransactionRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
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
     * 1. 지갑을 먼저 잠근다. 같은 구매자의 결제는 여기서 한 줄로 서므로, 아래 "읽고 판단하는" 일은 모두 잠금 뒤에 한다
     * 2. 같은 멱등 키로 이미 처리했으면 처음 결과를 그대로 반환 (돈은 다시 움직이지 않음)
     * 3. 다른 키인데 이미 PAID 결제가 있는 주문이면 409 (정상 흐름에서는 주문 쪽이 먼저 막으므로 버그 신호)
     * 4. 잔액이 결제 금액 이상인지 확인
     * 5. 결제 건 저장 (원장이 결제 ID를 가리켜야 하므로 원장보다 먼저)
     * 6. 원장 PAYMENT 기록 (이때 지갑 잔액 차감)
     * 7. 주문의 홀딩 생성 (HELD)
     * 동시 요청의 최후 안전장치: payment.idempotency_key, wallet_holding.order_id UNIQUE
     */
    public PayResultDto pay(PayCommand command) {
        Wallet wallet = walletRepository.findByMemberIdForUpdate(command.buyerId())
                .orElseThrow(() -> new DomainException(PaymentErrorCode.WALLET_NOT_FOUND));

        Optional<Payment> processed = paymentRepository.findByIdempotencyKey(command.idempotencyKey());
        if (processed.isPresent()) {
            return replay(processed.get(), command);
        }

        if (paymentRepository.existsByOrderIdAndStatus(command.orderId(), PaymentStatus.PAID)) {
            throw new DomainException(PaymentErrorCode.PAYMENT_ALREADY_PAID);
        }

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

    // 같은 멱등 키로 다시 온 요청: 처음 결과를 그대로 돌려준다
    // 키는 같은데 주문·구매자·금액이 다르면 다른 요청에 키를 재사용한 것이라 409
    private PayResultDto replay(Payment payment, PayCommand command) {
        if (!payment.getOrderId().equals(command.orderId())
                || !payment.getMemberId().equals(command.buyerId())
                || payment.getAmount() != command.amount()) {
            throw new DomainException(GlobalErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        // TODO 4단계: 실패한 결제 건(FAILED_*)도 처음 결과로 돌려준다
        return new PayResultDto(payment.getId(), PayResultStatus.PAID, 0, 0);
    }
}
