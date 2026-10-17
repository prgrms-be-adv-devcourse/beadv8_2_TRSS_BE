package com.backend.boundedContext.payment.app;

import java.util.List;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.domain.Payment;
import com.backend.boundedContext.payment.domain.PaymentStatus;
import com.backend.boundedContext.payment.out.PaymentRepository;
import com.backend.shared.payment.dto.PaymentAttemptDto;
import com.backend.shared.payment.dto.PaymentAttemptStatus;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentGetPaymentAttemptsUseCase {

    private final PaymentRepository paymentRepository;

    // 주문의 결제 시도 목록 (오래된 순, 실패 포함). 내 주문인지는 주문 쪽에서 확인하므로 orderId만 받음
    public List<PaymentAttemptDto> getPaymentAttempts(Long orderId) {
        return paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId).stream()
                .map(PaymentGetPaymentAttemptsUseCase::toDto)
                .toList();
    }

    private static PaymentAttemptDto toDto(Payment payment) {
        return new PaymentAttemptDto(
                payment.getId(),
                toAttemptStatus(payment.getStatus()),
                payment.getAmount(),
                payment.getCreatedAt(),
                payment.getPaidAt(),
                payment.getRefundedAt()
        );
    }

    // PaymentStatus에 값이 추가되면 컴파일 오류로 알 수 있게 switch로 하나씩 대응
    private static PaymentAttemptStatus toAttemptStatus(PaymentStatus status) {
        return switch (status) {
            case PAID -> PaymentAttemptStatus.PAID;
            case REFUNDED -> PaymentAttemptStatus.REFUNDED;
            case FAILED_INSUFFICIENT_BALANCE -> PaymentAttemptStatus.FAILED_INSUFFICIENT_BALANCE;
            case FAILED_ORDER_EXPIRED -> PaymentAttemptStatus.FAILED_ORDER_EXPIRED;
            case FAILED_SYSTEM_ERROR -> PaymentAttemptStatus.FAILED_SYSTEM_ERROR;
            // 결제 건은 PAID·FAILED_*로만 저장되므로 나올 수 없음
            case PROCESSING -> throw new IllegalStateException("진행 중 상태의 결제 건이 저장되어 있습니다: " + status);
        };
    }
}
