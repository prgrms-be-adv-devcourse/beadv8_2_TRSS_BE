package com.backend.boundedContext.payment.app;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.domain.Payment;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.PaymentPolicy;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletHolding;
import com.backend.boundedContext.payment.domain.WalletTransaction;
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
    private final PaymentPolicy paymentPolicy;

    /**
     * 결제 취소: 결제는 됐는데 주문 쪽 처리가 실패했을 때 홀딩 → 구매자 지갑으로 되돌린다 (주문 쪽 보상)
     * 결제 ID가 아니라 결제할 때 쓴 멱등 키로 찾는다. 응답 시간 초과처럼 결제 결과를 못 받은 경우에도 부를 수 있게 하기 위해서다.
     * 잠금 순서: 구매자 지갑 → 플랫폼 지갑(배송비가 있을 때) → 홀딩. 돈을 되돌리는 순서는 결제(Payment.cancel)가 정한다
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

        Wallet platformWallet = lockPlatformWalletIfNeeded(payment);
        WalletHolding holding = lockHolding(payment);

        List<WalletTransaction> transactions = payment.cancel(wallet, platformWallet, holding, LocalDateTime.now());
        walletTransactionRepository.saveAll(transactions);
        restoreTopups(payment);
    }

    private Wallet lockWallet(Long memberId) {
        return walletRepository.findByMemberIdForUpdate(memberId)
                .orElseThrow(() -> new DomainException(PaymentErrorCode.WALLET_NOT_FOUND));
    }

    // 배송비나 쿠폰 지원금이 있는 결제만 플랫폼(관리자) 지갑을 잠근다. 둘 다 없으면 null
    private Wallet lockPlatformWalletIfNeeded(Payment payment) {
        return payment.needsPlatformWallet() ? lockWallet(paymentPolicy.adminMemberId()) : null;
    }

    // 정산 지급과 동시에 바꾸지 않게 홀딩을 잠근다. 지급이 시작됐으면 Payment.cancel에서 409 (트랜잭션 전체 롤백)
    private WalletHolding lockHolding(Payment payment) {
        return walletHoldingRepository.findByPaymentIdForUpdate(payment.getId())
                .orElseThrow(() -> new DomainException(PaymentErrorCode.HOLDING_NOT_FOUND));
    }

    // 결제에 쓴 충전 건의 남은 금액을 되돌린다 (차감 내역은 이력으로 남김)
    private void restoreTopups(Payment payment) {
        topupUsageRepository.findAllByPaymentId(payment.getId())
                .forEach(usage -> usage.getTopup().restore(usage.getAmount()));
    }
}
