package com.backend.boundedContext.payment.app;

import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.domain.Payment;
import com.backend.boundedContext.payment.domain.PaymentPolicy;
import com.backend.boundedContext.payment.domain.PaymentRecords;
import com.backend.boundedContext.payment.domain.Wallet;
import com.backend.boundedContext.payment.domain.WalletTransactionType;
import com.backend.boundedContext.payment.out.PaymentRepository;
import com.backend.boundedContext.payment.out.WalletHoldingRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.boundedContext.payment.out.WalletTransactionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCreateSeedHoldingsUseCase {

    // 시드 충전 원장의 멱등 키. 같은 키는 한 번만 남으므로 앱을 다시 띄워도 두 번 충전하지 않는다
    private static final String PLATFORM_CHARGE_KEY = "SEED-PLATFORM-CHARGE";

    private final WalletRepository walletRepository;
    private final PaymentRepository paymentRepository;
    private final WalletHoldingRepository walletHoldingRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final PaymentPolicy paymentPolicy;

    /**
     * 개발용 결제 완료 건과 HELD 홀딩 생성 (정산 지급 로컬 확인용)
     * 실제 결제와 같은 길(ready → pay)을 탄다. 그러려면 구매자 지갑에 돈이 있어야 해서, 결제 금액만큼 시드 충전(CHARGE)부터 남긴다
     * 시드 충전은 실제 충전 건이 없어 참조 ID를 주문 ID로 둔다
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

            walletTransactionRepository.save(buyerWallet.change(
                    WalletTransactionType.CHARGE,
                    amount,
                    orderId,
                    "SEED-CHARGE-ORDER" + orderId
            ));
            // 주문을 거치지 않는 시드라 실제 주문번호가 없어 시드용 번호를 넣음 (V8 마이그레이션도 같은 형식)
            Payment payment = paymentRepository.save(Payment.ready(
                    orderId,
                    "SEED-ORDER" + orderId,
                    buyerWallet,
                    amount,
                    0,
                    0,
                    "SEED-PAYMENT-ORDER" + orderId
            ));
            PaymentRecords records = payment.pay(buyerWallet, null, now);
            walletTransactionRepository.saveAll(records.transactions());
            walletHoldingRepository.save(records.holding());
            log.info("홀딩 시드 생성: orderId={}, amount={}", orderId, amount);
        });
    }

    /**
     * 개발 서버·로컬에서 장바구니 쿠폰 지원금을 낼 수 있게 플랫폼(관리자) 지갑에 시드 충전을 한 번 넣는다
     * 이미 넣었거나(같은 멱등 키) 관리자 지갑이 아직 없으면 아무것도 하지 않는다
     */
    public void chargeSeedPlatformWallet(long amount) {
        if (walletTransactionRepository.findByIdempotencyKey(PLATFORM_CHARGE_KEY).isPresent()) return;

        Wallet platformWallet = walletRepository.findByMemberId(paymentPolicy.adminMemberId()).orElse(null);
        if (platformWallet == null) {
            log.info("관리자 지갑이 없어 플랫폼 지갑 시드 충전을 건너뜀: memberId={}", paymentPolicy.adminMemberId());
            return;
        }
        walletTransactionRepository.save(platformWallet.change(
                WalletTransactionType.CHARGE,
                amount,
                paymentPolicy.adminMemberId(),
                PLATFORM_CHARGE_KEY
        ));
        log.info("플랫폼 지갑 시드 충전: amount={}", amount);
    }
}
