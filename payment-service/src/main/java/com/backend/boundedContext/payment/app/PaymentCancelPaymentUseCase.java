package com.backend.boundedContext.payment.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.domain.Payment;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletTransaction;
import com.backend.boundedContext.payment.domain.WalletTransactionType;
import com.backend.boundedContext.payment.out.PaymentRepository;
import com.backend.boundedContext.payment.out.TopupUsageRepository;
import com.backend.boundedContext.payment.out.WalletHoldingRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.boundedContext.payment.out.WalletTransactionRepository;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentCancelPaymentUseCase {

    private final PaymentRepository paymentRepository;
    private final WalletRepository walletRepository;
    private final WalletHoldingRepository walletHoldingRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final TopupUsageRepository topupUsageRepository;

    /**
     * 결제 취소: 결제는 됐는데 주문 쪽 처리가 실패했을 때 홀딩 → 구매자 지갑으로 되돌린다 (주문 쪽 보상)
     * 결제 ID가 아니라 결제할 때 쓴 멱등 키로 찾는다. 응답 시간 초과처럼 결제 결과를 못 받은 경우에도 부를 수 있게 하기 위해서다.
     * 결제와 거울처럼 진행한다: 지갑 잠금 → 결제 건 CANCELED → 지갑 입금 → 충전 건 복원 → 홀딩 해제
     */
    public void cancel(String idempotencyKey) {
        // 이 키의 결제가 없으면(결제 요청이 도착하지 못함) 아무것도 하지 않는다
        paymentRepository.findMemberIdByIdempotencyKey(idempotencyKey)
                .ifPresent(buyerId -> cancelPayment(idempotencyKey, buyerId));
    }

    private void cancelPayment(String idempotencyKey, Long buyerId) {
        Wallet wallet = lockWallet(buyerId);
        Payment payment = paymentRepository.findByIdempotencyKey(idempotencyKey).orElseThrow();

        if (payment.isCanceled() || payment.isFailed()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        payment.cancel(now);
        refundToWallet(wallet, payment);
        restoreTopups(payment);
        releaseHolding(payment, now);
    }

    private Wallet lockWallet(Long buyerId) {
        return walletRepository.findByMemberIdForUpdate(buyerId)
                .orElseThrow(() -> new DomainException(PaymentErrorCode.WALLET_NOT_FOUND));
    }

    // 결제 금액을 구매자 지갑에 다시 넣고 원장 PAYMENT_CANCEL을 남긴다
    private void refundToWallet(Wallet wallet, Payment payment) {
        walletTransactionRepository.save(
                WalletTransaction.record(
                        wallet,
                        WalletTransactionType.PAYMENT_CANCEL,
                        payment.getAmount(),
                        payment.getId(),
                        payment.cancelIdempotencyKey()
                )
        );
    }

    // 결제에 쓴 충전 건의 남은 금액을 되돌린다 (차감 내역은 이력으로 남김)
    private void restoreTopups(Payment payment) {
        topupUsageRepository.findAllByPaymentId(payment.getId())
                .forEach(usage -> usage.getTopup().restore(usage.getAmount()));
    }

    // 홀딩 HELD → RELEASED. 정산 지급과 동시에 바꾸지 않게 잠그고, 지급이 시작됐으면 409 (트랜잭션 전체 롤백)
    private void releaseHolding(Payment payment, LocalDateTime now) {
        walletHoldingRepository.findByPaymentIdForUpdate(payment.getId())
                .orElseThrow(() -> new DomainException(PaymentErrorCode.HOLDING_NOT_FOUND))
                .release(now);
    }
}
