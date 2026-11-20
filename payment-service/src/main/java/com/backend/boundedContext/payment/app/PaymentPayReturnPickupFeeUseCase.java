package com.backend.boundedContext.payment.app;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.app.dto.InsufficientBalanceDto;
import com.backend.boundedContext.payment.app.dto.RefundDto;
import com.backend.boundedContext.payment.domain.Payment;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.PaymentPolicy;
import com.backend.boundedContext.payment.domain.PaymentRefund;
import com.backend.boundedContext.payment.domain.PaymentRefundStatus;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletHolding;
import com.backend.boundedContext.payment.domain.WalletTransaction;
import com.backend.boundedContext.payment.out.PaymentRefundRepository;
import com.backend.boundedContext.payment.out.WalletHoldingRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.boundedContext.payment.out.WalletTransactionRepository;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;

/**
 * 단순 변심 반품: 구매자가 회수 택배비를 예치금으로 결제하면 상품값을 환불한다 (한 트랜잭션)
 * 실제 반품은 회수 → 검수 → 환불이지만 회수·검수는 아직 없어서, 택배비를 내면 바로 환불한다
 * 1. 환불 기록을 처음부터 잠가서 읽는다. 두 번 눌러도 뒤 요청은 앞 요청이 끝난 뒤의 상태를 본다
 * 2. 이미 끝났으면(COMPLETED) 결과만 돌려준다. 결제 대기(PICKUP_FEE_REQUIRED)가 아니면 409
 * 3. 홀딩 → 지갑(회원 ID 오름차순) 순으로 잠근다 (② 환불·정산 지급과 같은 순서)
 * 4. 예치금이 회수 택배비보다 적으면 402와 충전 권장액. 환불금과 상관없이 택배비만큼 있어야 한다(택배비를 먼저 빼고 환불)
 * 5. 회수 택배비 결제(구매자 → 플랫폼) → 상품값 환불(Payment.refund) → 원장 저장
 */
@Component
@RequiredArgsConstructor
public class PaymentPayReturnPickupFeeUseCase {

    private final PaymentRefundRepository paymentRefundRepository;
    private final PaymentGetRefundUseCase paymentGetRefundUseCase;
    private final WalletHoldingRepository walletHoldingRepository;
    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final PaymentPolicy paymentPolicy;

    public RefundDto payPickupFee(Long buyerId, Long orderId) {
        PaymentRefund refund = paymentRefundRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new DomainException(PaymentErrorCode.REFUND_NOT_FOUND));
        Payment payment = paymentGetRefundUseCase.findBuyerPayment(orderId, buyerId);
        long pickupFee = paymentPolicy.returnPickupFee();

        if (refund.getStatus() == PaymentRefundStatus.COMPLETED && refund.isChangeOfMind()) {
            return RefundDto.of(refund, payment, pickupFee);
        }
        if (refund.getStatus() != PaymentRefundStatus.PICKUP_FEE_REQUIRED) {
            throw new DomainException(PaymentErrorCode.REFUND_PICKUP_FEE_NOT_REQUIRED);
        }

        WalletHolding holding = walletHoldingRepository.findByPaymentIdForUpdate(payment.getId())
                .orElseThrow(() -> new DomainException(PaymentErrorCode.HOLDING_NOT_FOUND));
        Map<Long, Wallet> wallets = lockWallets(payment.getMemberId(), paymentPolicy.adminMemberId());
        Wallet buyerWallet = wallets.get(payment.getMemberId());
        Wallet platformWallet = wallets.get(paymentPolicy.adminMemberId());

        if (!buyerWallet.canPay(pickupFee)) {
            long shortage = pickupFee - buyerWallet.getBalance();
            throw new DomainException(PaymentErrorCode.INSUFFICIENT_BALANCE,
                    new InsufficientBalanceDto(shortage, paymentPolicy.recommendedChargeAmount(shortage)));
        }

        List<WalletTransaction> transactions = new ArrayList<>(refund.payPickupFee(buyerWallet, platformWallet, pickupFee));
        transactions.addAll(payment.refund(buyerWallet, platformWallet, holding, refund, LocalDateTime.now()));
        walletTransactionRepository.saveAll(transactions);
        return RefundDto.of(refund, payment, pickupFee);
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
