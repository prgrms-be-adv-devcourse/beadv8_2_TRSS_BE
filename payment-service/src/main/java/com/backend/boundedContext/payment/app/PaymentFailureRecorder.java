package com.backend.boundedContext.payment.app;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backend.boundedContext.payment.domain.Payment;
import com.backend.boundedContext.payment.domain.PaymentStatus;
import com.backend.boundedContext.payment.out.PaymentRepository;
import com.backend.boundedContext.payment.out.WalletRepository;
import com.backend.shared.payment.dto.PayCommand;

import lombok.RequiredArgsConstructor;

/**
 * 결제 실패 기록 (FAILED_*)
 * 주문 쪽은 결제 결과가 PAID가 아니면 예외를 던져 주문 트랜잭션 전체를 롤백한다.
 * 같은 트랜잭션에 저장하면 실패 기록도 함께 사라지므로, 새 트랜잭션(REQUIRES_NEW)으로 먼저 커밋해 남긴다.
 * REQUIRES_NEW는 스프링 프록시를 거쳐 호출될 때만 동작하므로 PaymentPayUseCase 안의 메서드가 아니라 별도 빈으로 둔다.
 */
@Component
@RequiredArgsConstructor
public class PaymentFailureRecorder {

    private final PaymentRepository paymentRepository;
    private final WalletRepository walletRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment record(PayCommand command, Long walletId, PaymentStatus failStatus, String reason) {
        // 바깥 트랜잭션에서 가져온 지갑 엔티티는 이 트랜잭션의 것이 아니라서, ID로 참조만 다시 얻는다
        return paymentRepository.save(
                Payment.failed(
                    command.orderId(),
                    command.orderNumber(),
                    walletRepository.getReferenceById(walletId),
                    command.amount(),
                    command.idempotencyKey(),
                    failStatus,
                    reason
                )
        );
    }
}
