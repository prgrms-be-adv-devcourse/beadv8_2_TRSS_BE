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
     * 지갑을 먼저 잠근다. 같은 구매자의 결제·취소는 여기서 한 줄로 서므로, "읽고 판단하는" 일은 모두 잠금 뒤에 한다.
     * 주문 만료·잔액 부족은 예외가 아니라 FAILED_* 결과로 반환하고, 실패 기록은 별도 트랜잭션으로 남긴다.
     * 동시 요청의 최후 안전장치: payment.idempotency_key UNIQUE, wallet_holding 주문당 해제되지 않은 홀딩 1개
     */
    public PayResultDto pay(PayCommand command) {
        validateShippingFeeAndSubsidy(command);
        Wallet wallet = lockWallet(command.buyerId());

        Optional<Payment> processed = paymentRepository.findByIdempotencyKey(command.idempotencyKey());
        if (processed.isPresent()) {
            return returnPreviousResult(processed.get(), command, wallet);
        }
        validateNotPaidYet(command.orderId());

        LocalDateTime now = LocalDateTime.now();
        // 주문 쪽이 먼저 만료를 확인하지만, 계약상 결제 쪽에서도 한 번 더 막는다
        if (command.isExpiredAt(now)) {
            return fail(command, wallet, PaymentStatus.FAILED_ORDER_EXPIRED, "주문 결제 가능 시간이 지났습니다.");
        }
        if (!wallet.canPay(command.amount())) {
            return fail(command, wallet, PaymentStatus.FAILED_INSUFFICIENT_BALANCE, insufficientReason(wallet, command));
        }

        // 결제 건을 먼저 저장한다 (원장·홀딩이 결제 ID를 가리켜야 하므로)
        Payment payment = paymentRepository.save(Payment.paid(command.orderId(), command.orderNumber(), wallet,
                command.amount(), command.idempotencyKey(), now).withShippingFee(command.shippingFee()));
        withdrawFromWallet(wallet, payment);
        useTopups(command.buyerId(), payment);
        depositShippingFee(payment);
        walletHoldingRepository.save(WalletHolding.hold(payment, now));

        return toResult(payment, wallet);
    }

    // shortcut: 장바구니 쿠폰 지원금(COUPON_SUBSIDY)은 아직 처리하지 않아 0만 받는다. 장바구니 쿠폰을 켜기 전에 지원금 원장·홀딩 반영을 넣어야 한다
    private static void validateShippingFeeAndSubsidy(PayCommand command) {
        if (command.shippingFee() < 0 || command.shippingFee() >= command.amount()
                || command.couponSubsidyAmount() != 0) {
            throw new DomainException(GlobalErrorCode.INVALID_REQUEST);
        }
    }

    // 배송비는 홀딩(정산 대상)에 넣지 않고 플랫폼(관리자) 지갑으로 옮긴다. 구매자 지갑 차감은 PAYMENT 원장에 이미 포함돼 있다
    // shortcut: 배송비가 있는 결제마다 관리자 지갑 한 행을 잠가 결제끼리 잠깐 줄을 선다. 결제량이 늘면 플랫폼 지갑 반영을 비동기로 바꾼다
    private void depositShippingFee(Payment payment) {
        if (payment.getShippingFee() == 0) return;
        Wallet platformWallet = lockWallet(paymentPolicy.adminMemberId());
        walletTransactionRepository.save(WalletTransaction.record(platformWallet, WalletTransactionType.SHIPPING_FEE,
                payment.getShippingFee(), payment.getId(), payment.shippingFeeIdempotencyKey()));
    }

    private Wallet lockWallet(Long buyerId) {
        return walletRepository.findByMemberIdForUpdate(buyerId)
                .orElseThrow(() -> new DomainException(PaymentErrorCode.WALLET_NOT_FOUND));
    }

    // 다른 키인데 이미 PAID 결제가 있는 주문이면 409 (정상 흐름에서는 주문 쪽이 먼저 막으므로 버그 신호)
    private void validateNotPaidYet(Long orderId) {
        if (paymentRepository.existsByOrderIdAndStatus(orderId, PaymentStatus.PAID)) {
            throw new DomainException(PaymentErrorCode.PAYMENT_ALREADY_PAID);
        }
    }

    private static String insufficientReason(Wallet wallet, PayCommand command) {
        return "잔액 부족: 잔액 " + wallet.getBalance() + "원, 결제 금액 " + command.amount() + "원";
    }

    // 구매자 지갑에서 결제 금액을 빼고 원장 PAYMENT를 남긴다
    private void withdrawFromWallet(Wallet wallet, Payment payment) {
        walletTransactionRepository.save(WalletTransaction.record(wallet, WalletTransactionType.PAYMENT,
                payment.getAmount(), payment.getId(), payment.paymentIdempotencyKey()));
    }

    // 오래된 충전 건부터 결제 금액만큼 차감하고 차감 내역을 남긴다 (충전 취소 가능 여부 판단용)
    // 잔액에는 환불·정산으로 들어온 돈도 있어서, 충전 건을 다 써도 결제 금액이 남을 수 있다 (그 부분은 기록할 충전 건이 없음)
    private void useTopups(Long memberId, Payment payment) {
        long amountToCover = payment.getAmount();   // 충전 건에서 아직 빼야 할 금액
        for (Topup topup : topupRepository.findUsableForUpdate(memberId, TopupStatus.DONE)) {
            if (amountToCover == 0) break;
            long usedFromTopup = topup.usableFromRemaining(amountToCover);
            topupUsageRepository.save(TopupUsage.use(topup, payment, usedFromTopup));
            amountToCover -= usedFromTopup;
        }
    }

    // 같은 멱등 키로 다시 온 요청: 처음 결과를 그대로 돌려준다
    // 키는 같은데 주문·구매자·금액이 다르면 다른 요청에 키를 재사용한 것이라 409
    private PayResultDto returnPreviousResult(Payment payment, PayCommand command, Wallet wallet) {
        if (!payment.getOrderId().equals(command.orderId())
                || !payment.getMemberId().equals(command.buyerId())
                || payment.getAmount() != command.amount()
                || payment.getShippingFee() != command.shippingFee()) {
            throw new DomainException(GlobalErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return toResult(payment, wallet);
    }

    // 실패 기록은 별도 트랜잭션으로 먼저 커밋 → 주문 쪽이 롤백해도 남는다
    private PayResultDto fail(PayCommand command, Wallet wallet, PaymentStatus failStatus, String reason) {
        Payment failed = paymentFailureRecorder.record(command, wallet.getId(), failStatus, reason);
        return toResult(failed, wallet);
    }

    // 저장된 결제 건의 상태를 결과로 바꾼다 (새 결제와 같은 키로 다시 온 요청 모두)
    // 취소된 결제의 키로 다시 오면 이 키로는 더 진행할 수 없으므로 409 (새 키로 결제해야 함)
    private PayResultDto toResult(Payment payment, Wallet wallet) {
        return switch (payment.getStatus()) {
            case PAID -> new PayResultDto(payment.getId(), PayResultStatus.PAID, 0, 0);
            case FAILED_ORDER_EXPIRED -> new PayResultDto(payment.getId(), PayResultStatus.FAILED_ORDER_EXPIRED, 0, 0);
            case FAILED_INSUFFICIENT_BALANCE -> insufficientResult(payment, wallet);
            case CANCELED -> throw new DomainException(PaymentErrorCode.PAYMENT_CANCELED);
            default -> throw new IllegalStateException("결제 결과로 돌려줄 수 없는 상태입니다: " + payment.getStatus());
        };
    }

    // 잔액 부족 결과: 부족액(결제 금액 - 현재 잔액)과 충전 권장액(부족액을 충전 단위로 올림)
    // 같은 키로 다시 조회할 때도 현재 잔액으로 다시 계산한다 (부족액은 저장하지 않음)
    private PayResultDto insufficientResult(Payment payment, Wallet wallet) {
        long shortage = Math.max(0, payment.getAmount() - wallet.getBalance());
        return new PayResultDto(payment.getId(), PayResultStatus.FAILED_INSUFFICIENT_BALANCE,
                shortage, paymentPolicy.recommendedChargeAmount(shortage));
    }
}
