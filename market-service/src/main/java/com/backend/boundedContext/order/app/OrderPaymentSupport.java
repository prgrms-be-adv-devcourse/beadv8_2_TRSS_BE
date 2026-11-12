package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.boundedContext.order.out.OrderCouponPort;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.global.exception.DomainException;
import com.backend.shared.order.event.OrderPaidEvent;
import com.backend.shared.payment.dto.PayCommand;
import com.backend.shared.payment.dto.PayResultDto;
import com.backend.shared.payment.out.PaymentApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 결제 요청과 결과 반영 (결제 API의 3·4단계, 재확인 스케줄러가 함께 쓴다)
 *
 * 결제는 HTTP라 주문 트랜잭션에 참여하지 못한다. 그래서 주문을 PAYING으로 커밋한 뒤(호출한 쪽) 트랜잭션 밖에서 결제를 부르고,
 * 주문을 다시 잠가 **아직 PAYING이고 같은 멱등키일 때만** 결과를 반영한다. 결제 API와 재확인이 겹쳐도 결과는 한 번만 반영된다.
 * - PAID: 예약 쿠폰 사용 처리, 주문 PAID, 장바구니 항목 삭제, OrderPaidEvent(outbox)를 한 트랜잭션으로
 * - FAILED_*: 주문 PAYMENT_FAILED (다시 결제하거나, 만료되면 만료 스케줄러가 취소·재고 복구)
 * - payment가 요청을 거절(4xx): 결제가 일어나지 않았으므로 PAYMENT_FAILED로 두고 같은 오류를 다시 던진다
 * - 결과 불명(접속 실패·응답 시간 초과·5xx·반영 트랜잭션 실패): PAYING으로 둔다. 재확인 스케줄러가 같은 키로 pay를 다시 불러 정리한다
 * - PAID인데 주문이 이 키로 결제 중도 결제 완료도 아님(예외 상태): 결제를 취소(cancelPayment)해 돈을 돌려주고 ERROR 로그를 남긴다
 */
@Slf4j
@Component
public class OrderPaymentSupport {

    // payment의 4xx 중 결과를 확정할 수 없는 것: 같은 키의 다른 요청과 겹쳐 잠금·동시성 충돌이 난 경우 (먼저 온 요청이 결제됐을 수 있다)
    private static final Set<String> UNCERTAIN_REJECTIONS = Set.of(
            "409-LOCK_CONFLICT", "409-CONCURRENT_UPDATE", "409-DATA_CONFLICT", "429-TOO_MANY_REQUESTS");

    private final OrderRepository orderRepository;
    private final OrderSupport orderSupport;
    private final PaymentApi paymentApi;
    private final EventPublisher eventPublisher;
    private final OrderCouponPort orderCouponPort;
    private final TransactionTemplate transaction;

    public OrderPaymentSupport(OrderRepository orderRepository, OrderSupport orderSupport, PaymentApi paymentApi,
                               EventPublisher eventPublisher, OrderCouponPort orderCouponPort,
                               PlatformTransactionManager transactionManager) {
        this.orderRepository = orderRepository;
        this.orderSupport = orderSupport;
        this.paymentApi = paymentApi;
        this.eventPublisher = eventPublisher;
        this.orderCouponPort = orderCouponPort;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 결제 요청 내용. 주문의 금액·만료 시각은 생성 뒤 바뀌지 않으므로, 재확인에서 다시 만들어도 처음 요청과 같은 내용이다.
     * 장바구니 쿠폰 할인은 플랫폼 부담이라 같은 금액을 지원금으로 보낸다 (쿠폰 반영이 꺼져 있으면 0)
     */
    public PayCommand toPayCommand(Order order) {
        return new PayCommand(order.getId(), order.getOrderNumber(), order.getBuyerId(),
                order.getPayAmount(), order.getShippingFee(), order.getCartCouponDiscountAmount(),
                order.getExpiresAt(), order.getPaymentIdempotencyKey());
    }

    /**
     * 결제를 요청하고(트랜잭션 밖) 결과를 반영한다. 결과를 모르면 {@link PaymentAttempt#unknown()}.
     * payment가 요청을 거절(4xx)하면 주문을 PAYMENT_FAILED로 반영하고 그 DomainException을 다시 던진다.
     */
    public PaymentAttempt requestAndApply(PayCommand command, LocalDateTime now) {
        PayResultDto result;
        try {
            result = paymentApi.pay(command);
        } catch (DomainException e) {
            if (!isRejected(e)) {
                log.warn("결제 결과를 알 수 없어 주문을 결제 중으로 둡니다: orderId={}, key={}, reason={}",
                        command.orderId(), command.idempotencyKey(), e.getResultCode());
                return PaymentAttempt.unknown();
            }
            applyRejection(command, e);
            throw e;
        } catch (RuntimeException e) {
            log.warn("결제 결과를 알 수 없어 주문을 결제 중으로 둡니다: orderId={}, key={}",
                    command.orderId(), command.idempotencyKey(), e);
            return PaymentAttempt.unknown();
        }

        Order order;
        try {
            order = transaction.execute(status -> apply(command, result, now));
        } catch (RuntimeException e) {
            log.error("결제 결과 반영에 실패해 주문을 결제 중으로 둡니다. 재확인 스케줄러가 다시 반영합니다: orderId={}, key={}, result={}",
                    command.orderId(), command.idempotencyKey(), result.status(), e);
            return PaymentAttempt.unknown();
        }
        compensateIfNotReflected(command, result, order);
        return new PaymentAttempt(result, order);
    }

    // 4단계: 주문을 잠그고, 아직 이 키로 결제 중일 때만 결과를 반영한다
    private Order apply(PayCommand command, PayResultDto result, LocalDateTime now) {
        Order order = lockOrder(command.orderId());
        if (!order.isPayingWith(command.idempotencyKey())) {
            return order;   // 다른 쪽(결제 API·재확인)이 먼저 반영했거나 예외 상태
        }

        switch (result.status()) {
            case PAID -> {
                orderCouponPort.useReservedCoupons(order.getId(), now);
                order.completePayment(now);
                publishPaidEvent(order);
                removeCartItems(order);
            }
            case FAILED_INSUFFICIENT_BALANCE -> order.failPayment("잔액 부족");
            case FAILED_ORDER_EXPIRED -> order.failPayment("결제 가능 시간 만료");
            default -> throw new IllegalStateException("처리할 수 없는 결제 결과입니다: " + result.status());
        }
        return order;
    }

    // payment가 요청을 거절했다 = 결제가 일어나지 않았다. 반영에 실패하면 PAYING으로 남고 재확인이 같은 거절을 받아 다시 반영한다
    private void applyRejection(PayCommand command, DomainException rejection) {
        try {
            transaction.executeWithoutResult(status -> {
                Order order = lockOrder(command.orderId());
                if (order.isPayingWith(command.idempotencyKey())) {
                    order.failPayment("결제 요청 거절: " + rejection.getResultCode());
                }
            });
        } catch (RuntimeException e) {
            log.error("결제 거절 반영에 실패해 주문을 결제 중으로 둡니다: orderId={}, key={}",
                    command.orderId(), command.idempotencyKey(), e);
        }
    }

    // 결제는 됐는데 이 주문이 이 키로 PAID가 아니면(결제 중도 아님) 돈만 빠진 상태라 결제를 취소한다. 취소는 멱등이다
    private void compensateIfNotReflected(PayCommand command, PayResultDto result, Order order) {
        if (!result.isPaid() || order.isPaidWith(command.idempotencyKey())) {
            return;
        }
        log.error("결제는 됐지만 주문에 반영할 수 없어 결제를 취소합니다: orderId={}, key={}, orderStatus={}",
                command.orderId(), command.idempotencyKey(), order.getStatus());
        try {
            paymentApi.cancelPayment(command.idempotencyKey());
        } catch (RuntimeException e) {
            log.error("결제 취소(보상)에 실패했습니다. 수동 확인이 필요합니다: orderId={}, key={}",
                    command.orderId(), command.idempotencyKey(), e);
        }
    }

    // payment가 판단해 거절한 요청인지 (4xx). 잠금·동시성 충돌은 같은 키의 다른 요청이 결제됐을 수 있어 결과 불명으로 본다
    private static boolean isRejected(DomainException e) {
        int status = e.statusCode();
        return status >= 400 && status < 500 && !UNCERTAIN_REJECTIONS.contains(e.getResultCode());
    }

    private Order lockOrder(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new IllegalStateException("결제 중인 주문을 찾을 수 없습니다. orderId=" + orderId));
    }

    // 배송지가 있는 주문만 배송 흐름을 탄다. 배송지 없는 주문(프론트가 붙기 전)은 발행하지 않고 결제 기준으로 확정된다
    private void publishPaidEvent(Order order) {
        if (!order.hasShippingAddress()) {
            return;
        }
        eventPublisher.publish(new OrderPaidEvent(
                order.getId(),
                order.getOrderNumber(),
                order.getBuyerId(),
                order.getPaidAt(),
                order.getShippingAddress().toDto(),
                order.getItems().stream()
                        .map(OrderItem::toPaidOrderItemDto)
                        .toList()
        ));
    }

    // 장바구니 항목 삭제
    private void removeCartItems(Order order) {
        List<Long> cartItemIds = order.getItems().stream()
                .map(OrderItem::getCartItemId)
                .filter(Objects::nonNull)
                .toList();

        orderSupport.findCart(order.getBuyerId()).ifPresent(cart -> cart.removeItems(cartItemIds));
    }

    /**
     * 결제 요청 결과. result가 null이면 결과 불명(주문은 PAYING).
     * order는 반영 트랜잭션이 끝난 뒤의 주문이다 (다른 쪽이 먼저 반영했으면 그 상태)
     */
    public record PaymentAttempt(PayResultDto result, Order order) {
        static PaymentAttempt unknown() {
            return new PaymentAttempt(null, null);
        }

        public boolean isUnknown() {
            return result == null;
        }
    }
}
