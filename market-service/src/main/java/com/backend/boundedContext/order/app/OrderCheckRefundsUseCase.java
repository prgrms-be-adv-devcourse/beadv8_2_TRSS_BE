package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.payment.dto.PaymentAttemptDto;
import com.backend.shared.payment.dto.PaymentAttemptStatus;
import com.backend.shared.payment.out.PaymentApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 반품 환불 확인 (스케줄러)
 * 반품을 승인하면 OrderReturnedEvent로 결제 서비스에 환불을 넘기고 끝나므로, 주문은 환불이 실제로 됐는지 모른다.
 * 이벤트로 넘긴 일의 결과는 보낸 쪽이 다시 확인해야 유실을 알 수 있다(결제 PAYING 재확인과 같은 원칙).
 * - 결제 시도 조회(getPaymentAttempts)에 REFUNDED가 있으면 그 환불 시각을 주문에 기록한다
 * - 아직 없으면 그대로 둔다. 반품 승인 후 refundAlertMinutes가 지나도 없으면 ERROR 로그로 한 번 알린다(refund_alerted_at)
 * 결제 조회는 HTTP라 트랜잭션 밖에서 하고, 기록할 때만 주문을 잠그고 다시 확인한다.
 */
@Slf4j
@Component
public class OrderCheckRefundsUseCase {

    private final OrderRepository orderRepository;
    private final PaymentApi paymentApi;
    private final TransactionTemplate transaction;
    private final int refundCheckSeconds;
    private final int refundAlertMinutes;

    public OrderCheckRefundsUseCase(OrderRepository orderRepository, PaymentApi paymentApi,
                                    PlatformTransactionManager transactionManager,
                                    @Value("${custom.order.refundCheckSeconds}") int refundCheckSeconds,
                                    @Value("${custom.order.refundAlertMinutes}") int refundAlertMinutes) {
        this.orderRepository = orderRepository;
        this.paymentApi = paymentApi;
        this.transaction = new TransactionTemplate(transactionManager);
        this.refundCheckSeconds = refundCheckSeconds;
        this.refundAlertMinutes = refundAlertMinutes;
    }

    // 확인 대상 주문 id 조회: 반품 승인 뒤 refundCheckSeconds가 지났는데 환불 기록이 없는 주문
    public List<Long> findTargetIds(LocalDateTime now, Long afterId, int limit) {
        return orderRepository.findRefundCheckTargetIds(now.minusSeconds(refundCheckSeconds), afterId, PageRequest.of(0, limit));
    }

    /**
     * 주문 한 건 확인. 환불 시각을 기록했으면 true, 아직 환불 전이거나 결제 서비스가 응답하지 않으면 false.
     * 결제 서비스 장애는 WARN만 남기고 다음 실행에서 다시 확인한다(장애가 길어지면 결국 ERROR 알림이 난다).
     */
    public boolean check(Long orderId, LocalDateTime now) {
        Optional<Order> found = orderRepository.findById(orderId);
        if (found.isEmpty() || !found.get().isRefundPending()) {
            return false;
        }
        Order order = found.get();

        List<PaymentAttemptDto> attempts;
        try {
            attempts = paymentApi.getPaymentAttempts(orderId);
        } catch (DomainException e) {
            log.warn("반품 환불 확인 중 결제 조회에 실패했습니다: orderId={}, reason={}", orderId, e.getResultCode());
            alertIfStale(order, now);
            return false;
        }

        Optional<LocalDateTime> refundedAt = attempts.stream()
                .filter(attempt -> attempt.status() == PaymentAttemptStatus.REFUNDED)
                .map(PaymentAttemptDto::refundedAt)
                .filter(Objects::nonNull)
                .findFirst();
        if (refundedAt.isEmpty()) {
            alertIfStale(order, now);
            return false;
        }

        // 잠근 뒤 다시 확인한다: 그사이 다른 실행이 이미 기록했으면 false
        return Boolean.TRUE.equals(transaction.execute(status -> orderRepository.findByIdForUpdate(orderId)
                .map(locked -> locked.markRefunded(refundedAt.get()))
                .orElse(false)));
    }

    // 반품 승인 후 오래 환불되지 않은 주문은 사람이 봐야 한다 (돈이 홀딩에 남아 있다)
    // 알림은 주문마다 한 번만: 잠근 뒤 알림 시각을 기록할 수 있을 때만 ERROR를 남긴다(5분마다 같은 알림이 반복되지 않게)
    private void alertIfStale(Order order, LocalDateTime now) {
        LocalDateTime returnedAt = order.getReturnedAt();
        if (order.getRefundAlertedAt() != null || returnedAt == null
                || now.isBefore(returnedAt.plusMinutes(refundAlertMinutes))) {
            return;
        }
        boolean first = Boolean.TRUE.equals(transaction.execute(status -> orderRepository.findByIdForUpdate(order.getId())
                .map(locked -> locked.markRefundAlerted(now))
                .orElse(false)));
        if (first) {
            log.error("반품 승인 후 {}분이 지나도 환불되지 않았습니다: orderId={}, returnedAt={}",
                    refundAlertMinutes, order.getId(), returnedAt);
        }
    }
}
