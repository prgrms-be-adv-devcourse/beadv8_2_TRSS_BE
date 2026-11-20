package com.backend.boundedContext.payment.app;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.backend.boundedContext.payment.domain.Payment;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.PaymentPolicy;
import com.backend.boundedContext.payment.domain.PaymentRefund;
import com.backend.boundedContext.payment.domain.PaymentRefundStatus;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletHolding;
import com.backend.boundedContext.payment.domain.WalletTransaction;
import com.backend.boundedContext.payment.out.PaymentRefundRepository;
import com.backend.boundedContext.payment.out.PaymentRepository;
import com.backend.boundedContext.payment.out.WalletHoldingRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.boundedContext.payment.out.WalletTransactionRepository;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;

/**
 * 반품 환불 ② 환불: 환불 기록을 잠그고, 결제를 확인한 뒤 홀딩에서 돈을 나눈다 (Payment.refund)
 * 다시 해도 결과가 같은 실패(결제 완료 건 없음·금액 불일치)는 돈을 옮기지 않고 이 트랜잭션에서 바로 기록한다.
 * 돈을 옮기다 막힌 경우(정산된 홀딩 등)와 그 밖의 오류는 예외로 롤백되고, 호출한 쪽이 ③ 실패 기록을 부른다
 * 잠금 순서: 환불 기록 → 홀딩 → 지갑(회원 ID 오름차순, 정산 지급과 같은 순서라 서로 기다리다 멈추지 않는다)
 */
@Component
@RequiredArgsConstructor
public class PaymentRefundUseCase {

    private final PaymentRefundRepository paymentRefundRepository;
    private final PaymentRepository paymentRepository;
    private final WalletHoldingRepository walletHoldingRepository;
    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final PaymentPolicy paymentPolicy;

    @Transactional
    public void refund(Long refundId) {
        PaymentRefund refund = paymentRefundRepository.findByIdForUpdate(refundId).orElseThrow();
        // 이미 끝난 환불, 사람이 볼 실패, 재시도를 다 쓴 환불은 건드리지 않는다 (다시 받은 메시지·겹친 처리)
        if (!refund.isProcessable()) return;

        Optional<Payment> paidPayment = findPaidPayment(refund.getOrderId());
        if (paidPayment.isEmpty()) {
            refund.fail(PaymentRefundStatus.FAILED_PAYMENT_NOT_PAID, null, "주문에 결제 완료(PAID) 건이 없음");
            return;
        }
        Payment payment = paidPayment.get();
        if (payment.getAmount() != refund.getRequestedAmount()) {
            refund.fail(PaymentRefundStatus.FAILED_AMOUNT_MISMATCH, payment.getId(),
                    "이벤트 환불 금액 " + refund.getRequestedAmount() + "원 ≠ 결제 금액 " + payment.getAmount() + "원");
            return;
        }

        WalletHolding holding = walletHoldingRepository.findByPaymentIdForUpdate(payment.getId())
                .orElseThrow(() -> new DomainException(PaymentErrorCode.HOLDING_NOT_FOUND));

        // 단순 변심: 구매자가 반품 회수 택배비를 결제해야 환불한다. 여기서는 환불할 수 있는지만 보고 결제 대기로 둔다(돈은 그대로)
        // 정산이 시작된 홀딩이면 택배비를 받아도 환불할 수 없으니 지금 막는다(③에서 FAILED_HOLDING_SETTLED)
        if (refund.isChangeOfMind()) {
            if (!holding.isReleasable()) throw new DomainException(PaymentErrorCode.REFUND_HOLDING_SETTLED);
            refund.requirePickupFee(payment.getId());
            return;
        }

        Map<Long, Wallet> wallets = lockWallets(payment.getMemberId(), paymentPolicy.adminMemberId());
        List<WalletTransaction> transactions = payment.refund(
                wallets.get(payment.getMemberId()),
                wallets.get(paymentPolicy.adminMemberId()),
                holding,
                refund,
                LocalDateTime.now());
        walletTransactionRepository.saveAll(transactions);
    }

    // 주문의 결제 시도 중 결제 완료 건
    private Optional<Payment> findPaidPayment(Long orderId) {
        return paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId).stream()
                .filter(Payment::isPaid)
                .findFirst();
    }

    // 데드락을 막기 위해 회원 ID 오름차순으로 잠근다. 구매자와 관리자가 같은 회원이면 한 번만 잠근다
    private Map<Long, Wallet> lockWallets(Long buyerMemberId, Long adminMemberId) {
        Map<Long, Wallet> wallets = new TreeMap<>();
        for (Long memberId : new TreeSet<>(List.of(buyerMemberId, adminMemberId))) {
            Wallet wallet = walletRepository.findByMemberIdForUpdate(memberId)
                    .orElseThrow(() -> new DomainException(PaymentErrorCode.WALLET_NOT_FOUND));
            wallets.put(memberId, wallet);
        }
        return wallets;
    }
}
