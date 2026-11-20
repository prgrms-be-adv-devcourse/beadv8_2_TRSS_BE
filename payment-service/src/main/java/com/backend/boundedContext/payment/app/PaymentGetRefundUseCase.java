package com.backend.boundedContext.payment.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.app.dto.RefundDto;
import com.backend.boundedContext.payment.domain.Payment;
import com.backend.boundedContext.payment.domain.PaymentErrorCode;
import com.backend.boundedContext.payment.domain.PaymentPolicy;
import com.backend.boundedContext.payment.domain.PaymentRefund;
import com.backend.boundedContext.payment.domain.PaymentStatus;
import com.backend.boundedContext.payment.out.PaymentRefundRepository;
import com.backend.boundedContext.payment.out.PaymentRepository;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;

/**
 * 구매자 반품 환불 조회: 환불 상태와 받을 금액, 내야 할 회수 택배비 (단순 변심이면 PICKUP_FEE_REQUIRED일 때 결제 버튼을 보여 준다)
 * 남의 주문이거나 환불 기록이 없으면 같은 404로 답한다(있는지 드러내지 않음)
 */
@Component
@RequiredArgsConstructor
public class PaymentGetRefundUseCase {

    private final PaymentRefundRepository paymentRefundRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentPolicy paymentPolicy;

    public RefundDto getRefund(Long buyerId, Long orderId) {
        PaymentRefund refund = paymentRefundRepository.findByOrderId(orderId)
                .orElseThrow(() -> new DomainException(PaymentErrorCode.REFUND_NOT_FOUND));
        return RefundDto.of(refund, findBuyerPayment(orderId, buyerId), paymentPolicy.returnPickupFee());
    }

    // 이 구매자가 결제한 주문의 결제 건(결제 완료 또는 환불됨). 남의 주문이면 404
    Payment findBuyerPayment(Long orderId, Long buyerId) {
        return paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId).stream()
                .filter(payment -> payment.getMemberId().equals(buyerId))
                .filter(payment -> payment.getStatus() == PaymentStatus.PAID || payment.getStatus() == PaymentStatus.REFUNDED)
                .findFirst()
                .orElseThrow(() -> new DomainException(PaymentErrorCode.REFUND_NOT_FOUND));
    }
}
