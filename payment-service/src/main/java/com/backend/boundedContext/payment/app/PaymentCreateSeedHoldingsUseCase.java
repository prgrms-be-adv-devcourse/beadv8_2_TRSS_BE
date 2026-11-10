package com.backend.boundedContext.payment.app;

import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.domain.Payment;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletHolding;
import com.backend.boundedContext.payment.out.PaymentRepository;
import com.backend.boundedContext.payment.out.WalletHoldingRepository;
import com.backend.boundedContext.payment.out.WalletRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCreateSeedHoldingsUseCase {

    private final WalletRepository walletRepository;
    private final PaymentRepository paymentRepository;
    private final WalletHoldingRepository walletHoldingRepository;

    /**
     * 개발용 결제 완료 건과 HELD 홀딩 생성 (정산 지급 로컬 확인용)
     * 실제 결제(pay)를 거치지 않으므로 구매자 지갑 잔액·원장은 바꾸지 않는다
     * 구매자 지갑이 없으면(회원 시드를 건너뛴 환경) 아무것도 하지 않고, 이미 홀딩이 있는 주문은 건너뛴다(멱등)
     */
    public void createSeedHoldings(Long buyerMemberId, Map<Long, Long> amountByOrderId) {
        Wallet buyerWallet = walletRepository.findByMemberId(buyerMemberId).orElse(null);
        if (buyerWallet == null) {
            log.info("구매자 지갑이 없어 홀딩 시드를 건너뜀: memberId={}", buyerMemberId);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        amountByOrderId.forEach((orderId, amount) -> {
            if (walletHoldingRepository.existsByOrderId(orderId)) return;

            // 주문을 거치지 않는 시드라 실제 주문번호가 없어 시드용 번호를 넣음 (V8 마이그레이션도 같은 형식)
            Payment payment = paymentRepository.save(
                    Payment.paid(
                            orderId,
                            "SEED-ORDER" + orderId,
                            buyerWallet,
                            amount,
                            "SEED-PAYMENT-ORDER" + orderId,
                            now)
            );
            walletHoldingRepository.save(WalletHolding.hold(payment, now));
            log.info("홀딩 시드 생성: orderId={}, amount={}", orderId, amount);
        });
    }
}
