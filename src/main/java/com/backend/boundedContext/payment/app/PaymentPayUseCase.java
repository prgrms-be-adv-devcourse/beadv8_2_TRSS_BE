package com.backend.boundedContext.payment.app;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.domain.Payment;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.PaymentPolicy;
import com.backend.boundedContext.payment.domain.PaymentStatus;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.domain.TopupUsage;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletHolding;
import com.backend.boundedContext.payment.domain.WalletTransaction;
import com.backend.boundedContext.payment.domain.WalletTransactionType;
import com.backend.boundedContext.payment.out.PaymentRepository;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.boundedContext.payment.out.TopupUsageRepository;
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
    private final PaymentFailureRecorder paymentFailureRecorder;
    private final PaymentPolicy paymentPolicy;
    private final TopupRepository topupRepository;
    private final TopupUsageRepository topupUsageRepository;

    /**
     * 주문 결제: 구매자 지갑 → 홀딩
     * 1. 지갑을 먼저 잠근다. 같은 구매자의 결제는 여기서 한 줄로 서므로, 아래 "읽고 판단하는" 일은 모두 잠금 뒤에 한다
     * 2. 같은 멱등 키로 이미 처리했으면 처음 결과를 그대로 반환 (돈은 다시 움직이지 않음)
     * 3. 다른 키인데 이미 PAID 결제가 있는 주문이면 409 (정상 흐름에서는 주문 쪽이 먼저 막으므로 버그 신호)
     * 4. 주문 만료·잔액 부족은 예외가 아니라 FAILED_* 결과로 반환하고, 실패 기록은 별도 트랜잭션으로 남긴다
     * 5. 결제 건 저장 (원장이 결제 ID를 가리켜야 하므로 원장보다 먼저)
     * 6. 원장 PAYMENT 기록 (이때 지갑 잔액 차감)
     * 7. 오래된 충전 건부터 차감하고 차감 내역 기록 (topup_usage)
     * 8. 주문의 홀딩 생성 (HELD)
     * 동시 요청의 최후 안전장치: payment.idempotency_key, wallet_holding.order_id UNIQUE
     */
    public PayResultDto pay(PayCommand command) {
        Wallet wallet = walletRepository.findByMemberIdForUpdate(command.buyerId())
                .orElseThrow(() -> new DomainException(PaymentErrorCode.WALLET_NOT_FOUND));

        Optional<Payment> processed = paymentRepository.findByIdempotencyKey(command.idempotencyKey());
        if (processed.isPresent()) {
            return replay(processed.get(), command, wallet);
        }

        if (paymentRepository.existsByOrderIdAndStatus(command.orderId(), PaymentStatus.PAID)) {
            throw new DomainException(PaymentErrorCode.PAYMENT_ALREADY_PAID);
        }

        LocalDateTime now = LocalDateTime.now();

        // 주문 쪽이 먼저 만료를 확인하지만, 계약상 결제 쪽에서도 한 번 더 막는다
        if (!now.isBefore(command.orderExpiresAt())) {
            return fail(
                    command,
                    wallet,
                    PaymentStatus.FAILED_ORDER_EXPIRED,
                    "주문 결제 가능 시간이 지났습니다."
            );
        }

        if (wallet.getBalance() < command.amount()) {
            return fail(
                    command,
                    wallet,
                    PaymentStatus.FAILED_INSUFFICIENT_BALANCE,
                    "잔액 부족: 잔액 " + wallet.getBalance() + "원, 결제 금액 " + command.amount() + "원"
            );
        }

        Payment payment = paymentRepository.save(
                Payment.paid(
                        command.orderId(),
                        command.orderNumber(),
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
        useTopups(command.buyerId(), payment);
        walletHoldingRepository.save(
                WalletHolding.hold(
                        payment,
                        now
                )
        );

        return toResult(payment, wallet);
    }

    // 오래된 충전 건부터 결제 금액만큼 차감하고 차감 내역을 남긴다 (충전 취소 가능 여부 판단용)
    // 잔액에는 환불·정산으로 들어온 돈도 있어서, 충전 건을 다 써도 결제 금액이 남을 수 있다 (그 부분은 기록할 충전 건이 없음)
    private void useTopups(Long memberId, Payment payment) {
        long left = payment.getAmount();
        for (Topup topup : topupRepository.findUsableForUpdate(memberId, TopupStatus.DONE)) {
            if (left == 0) break;
            long used = Math.min(left, topup.getRemainingAmount());
            topupUsageRepository.save(
                    TopupUsage.use(topup, payment, used)
            );
            left -= used;
        }
    }

    // 같은 멱등 키로 다시 온 요청: 처음 결과를 그대로 돌려준다
    // 키는 같은데 주문·구매자·금액이 다르면 다른 요청에 키를 재사용한 것이라 409
    private PayResultDto replay(Payment payment, PayCommand command, Wallet wallet) {
        if (!payment.getOrderId().equals(command.orderId())
                || !payment.getMemberId().equals(command.buyerId())
                || payment.getAmount() != command.amount()) {
            throw new DomainException(GlobalErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return toResult(payment, wallet);
    }

    // 실패 기록은 별도 트랜잭션으로 먼저 커밋 → 주문 쪽이 롤백해도 남는다
    private PayResultDto fail(PayCommand command, Wallet wallet, PaymentStatus failStatus, String reason) {
        Payment failed = paymentFailureRecorder.record(
                command,
                wallet.getId(),
                failStatus,
                reason
        );
        return toResult(failed, wallet);
    }

    // 저장된 결제 건의 상태를 결과로 바꾼다
    // 잔액 부족이면 부족액(결제 금액 - 현재 잔액)과 충전 권장액(부족액을 충전 단위로 올림)을 채운다
    // 같은 키로 다시 조회할 때도 현재 잔액으로 다시 계산한다 (부족액은 저장하지 않음)
    private PayResultDto toResult(Payment payment, Wallet wallet) {
        return switch (payment.getStatus()) {
            case PAID -> new PayResultDto(payment.getId(), PayResultStatus.PAID, 0, 0);
            case FAILED_ORDER_EXPIRED -> new PayResultDto(payment.getId(), PayResultStatus.FAILED_ORDER_EXPIRED, 0, 0);
            case FAILED_INSUFFICIENT_BALANCE -> {
                long shortage = Math.max(0, payment.getAmount() - wallet.getBalance());
                yield new PayResultDto(
                        payment.getId(),
                        PayResultStatus.FAILED_INSUFFICIENT_BALANCE,
                        shortage,
                        paymentPolicy.recommendedChargeAmount(shortage)
                );
            }
            default -> throw new IllegalStateException("결제 결과로 돌려줄 수 없는 상태입니다: " + payment.getStatus());
        };
    }
}
