package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.app.OrderPaymentSupport.PaymentAttempt;
import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.order.dto.OrderStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 결제 중(PAYING) 주문 재확인 (스케줄러)
 * PAYING이 된 지 payingRecheckSeconds가 지난 주문에 같은 키·같은 내용으로 pay를 다시 불러 확정한다.
 * (읽기 조회의 "결제 없음"은 원래 요청이 늦게 도착할 수 있어 확정이 아니다. 같은 키는 payment가 처음 결과를 돌려준다)
 * - PAID → PAID (만료 시각이 지났어도)
 * - FAILED_* → PAYMENT_FAILED (만료된 주문은 만료 스케줄러가 취소·복구)
 * - 응답 없음·503-INTERNAL_API_AUTH_FAILED → 결과 불명이라 PAYING 유지. payingAlertMinutes가 지나면 ERROR 로그
 */
@Slf4j
@Component
public class OrderRecheckPayingOrdersUseCase {

    private final OrderRepository orderRepository;
    private final OrderPaymentSupport orderPaymentSupport;
    private final int payingRecheckSeconds;
    private final int payingAlertMinutes;

    public OrderRecheckPayingOrdersUseCase(OrderRepository orderRepository, OrderPaymentSupport orderPaymentSupport,
                                           @Value("${custom.order.payingRecheckSeconds}") int payingRecheckSeconds,
                                           @Value("${custom.order.payingAlertMinutes}") int payingAlertMinutes) {
        this.orderRepository = orderRepository;
        this.orderPaymentSupport = orderPaymentSupport;
        this.payingRecheckSeconds = payingRecheckSeconds;
        this.payingAlertMinutes = payingAlertMinutes;
    }

    // 재확인 대상 주문 id 조회
    public List<Long> findTargetIds(LocalDateTime now, Long afterId, int limit) {
        return orderRepository.findPayingRecheckTargetIds(now.minusSeconds(payingRecheckSeconds), afterId, PageRequest.of(0, limit));
    }

    /**
     * 주문 한 건 재확인. 결과를 반영했으면 true, 이미 정리됐거나 아직 결과를 모르면 false.
     * 주문은 잠그지 않고 읽는다: 결과 반영은 OrderPaymentSupport가 잠근 뒤 PAYING·같은 키를 다시 확인한다
     */
    public boolean recheck(Long orderId, LocalDateTime now) {
        Optional<Order> found = orderRepository.findById(orderId);
        if (found.isEmpty() || found.get().getStatus() != OrderStatus.PAYING) {
            return false;
        }
        Order order = found.get();

        PaymentAttempt attempt;
        try {
            attempt = orderPaymentSupport.recheckAndApply(orderPaymentSupport.toPayCommand(order), now);
        } catch (DomainException rejected) {
            // payment가 요청을 거절했다(결제 없음). 주문은 PAYMENT_FAILED로 반영됐다
            log.warn("결제 재확인 요청이 거절돼 결제 실패로 반영했습니다: orderId={}, reason={}", orderId, rejected.getResultCode());
            return true;
        }

        if (attempt.isUnknown()) {
            alertIfStale(order, now);
            return false;
        }
        return true;
    }

    // 오래 결제 중으로 남은 주문은 사람이 봐야 한다 (주문 ID·키만 남긴다)
    private void alertIfStale(Order order, LocalDateTime now) {
        LocalDateTime startedAt = order.getPayingStartedAt();
        if (startedAt != null && !now.isBefore(startedAt.plusMinutes(payingAlertMinutes))) {
            log.error("결제 중 상태가 {}분 넘게 정리되지 않았습니다: orderId={}, key={}, payingStartedAt={}",
                    payingAlertMinutes, order.getId(), order.getPaymentIdempotencyKey(), startedAt);
        }
    }
}
