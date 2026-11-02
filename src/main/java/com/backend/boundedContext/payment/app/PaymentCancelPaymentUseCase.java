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
     * 1. 이 키의 결제가 없으면(결제 요청이 도착하지 못함) 아무것도 하지 않는다
     * 2. 지갑을 먼저 잠근다 (결제와 같은 순서). 같은 구매자의 결제·취소는 여기서 한 줄로 서고, 결제 건은 잠금 뒤에 새로 읽는다
     * 3. 실패 건(FAILED_*)은 돈이 움직이지 않았고, 이미 취소된 건은 처리가 끝났으므로 아무것도 하지 않는다 (여러 번 불러도 한 번만 취소)
     * 4. 결제 CANCELED, 홀딩 RELEASED (정산 지급이 시작됐으면 409), 원장 PAYMENT_CANCEL(지갑 입금)
     * 5. 결제에 쓴 충전 건의 남은 금액을 되돌린다 (차감 내역은 이력으로 남김)
     */
    public void cancel(String idempotencyKey) {
        Optional<Long> buyerId = paymentRepository.findMemberIdByIdempotencyKey(idempotencyKey);
        if (buyerId.isEmpty()) {
            return;
        }
        Wallet wallet = walletRepository.findByMemberIdForUpdate(buyerId.get())
                .orElseThrow(() -> new DomainException(PaymentErrorCode.WALLET_NOT_FOUND));

        Payment payment = paymentRepository.findByIdempotencyKey(idempotencyKey).orElseThrow();

        if (payment.getStatus() == PaymentStatus.CANCELED || payment.getStatus().isFailure()) {
            return;
        }
        if (!payment.isPaid()) {
            throw new DomainException(PaymentErrorCode.PAYMENT_NOT_CANCELABLE);
        }

        LocalDateTime now = LocalDateTime.now();

        WalletHolding holding = walletHoldingRepository.findByPaymentIdForUpdate(payment.getId())
                .orElseThrow(() -> new DomainException(PaymentErrorCode.HOLDING_NOT_FOUND));
        holding.release(now);
        payment.cancel(now);

        walletTransactionRepository.save(
                WalletTransaction.record(
                        wallet,
                        WalletTransactionType.PAYMENT_CANCEL,
                        payment.getAmount(),
                        payment.getId(),
                        payment.cancelIdempotencyKey()
                )
        );
        topupUsageRepository.findAllByPayment_Id(payment.getId())
                .forEach(usage -> usage.getTopup().restore(usage.getAmount()));
    }
}
