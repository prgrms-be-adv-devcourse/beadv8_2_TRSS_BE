package com.backend.boundedContext.payment.app;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.backend.boundedContext.payment.domain.PaymentRefund;
import com.backend.boundedContext.payment.out.PaymentRefundRepository;
import com.backend.shared.order.event.OrderReturnedEvent;

import lombok.RequiredArgsConstructor;

/**
 * 반품 환불 ① 요청 기록: 환불을 하기 전에 REQUESTED로 먼저 커밋해 둔다.
 * 그래야 환불(②)이 실패해도 이 행과 실패 사유가 남는다.
 * 같은 주문이면 새로 만들지 않고 기존 기록을 돌려준다(order_id 유니크). Kafka가 같은 메시지를 다시 줘도,
 * 이벤트 ID가 달라도 주문당 환불은 하나다. 다시 받은 메시지는 ②에서 기록 상태를 보고 이어서 처리하거나 멈춘다
 * 트랜잭션을 메서드마다 따로 열어야 해서 ②·③과 다른 빈으로 둔다 (같은 빈 안의 호출은 @Transactional이 적용되지 않음)
 */
@Component
@RequiredArgsConstructor
public class PaymentRequestRefundUseCase {

    private final PaymentRefundRepository paymentRefundRepository;

    @Transactional
    public Long request(OrderReturnedEvent event) {
        // 다른 이벤트로 같은 주문이 동시에 오면 order_id 유니크에 걸려 이 트랜잭션이 실패하고, Kafka 재시도 때 기존 기록을 찾는다
        return paymentRefundRepository.findByOrderId(event.orderId())
                .orElseGet(() -> paymentRefundRepository.save(
                        PaymentRefund.request(event.orderId(), event.refundAmount(), event.returnReasonCode()))
                ).getId();
    }
}
