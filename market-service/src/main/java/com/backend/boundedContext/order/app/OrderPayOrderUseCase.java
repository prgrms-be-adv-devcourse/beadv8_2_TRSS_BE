package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.boundedContext.order.in.dto.InsufficientBalanceResponse;
import com.backend.boundedContext.order.in.dto.OrderPayResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.event.OrderPaidEvent;
import com.backend.shared.payment.dto.PayCommand;
import com.backend.shared.payment.dto.PayResultDto;
import com.backend.shared.payment.out.PaymentApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 예치금 결제
 * 주문 PAID 변경 → 결제(지갑 → 홀딩)를 한 트랜잭션으로 처리한다.
 * 배송지가 있는 주문이면 결제 완료 이벤트(OrderPaidEvent)를 outbox에 저장한다. delivery가 받아 배송 건(READY)을 만든다.
 * 결제가 실패하면(예외) 주문 변경과 함께 이벤트 저장도 롤백된다.
 */
@Component
@RequiredArgsConstructor
public class OrderPayOrderUseCase {

    private final OrderRepository orderRepository;
    private final OrderSupport orderSupport;
    private final PaymentApi paymentApi;
    private final MemberApi memberApi;
    private final EventPublisher eventPublisher;

    public OrderPayResponse pay(Long buyerId, Long orderId) {
        // 탈퇴한 회원은 남은 Access Token으로 결제할 수 없게 401
        memberApi.checkActiveMember(buyerId);

        LocalDateTime now = LocalDateTime.now();

        Order order = orderRepository.findByIdAndBuyerIdForUpdate(orderId, buyerId)
                .orElseThrow(() -> new DomainException(OrderErrorCode.ORDER_NOT_FOUND));

        long remainingSeconds = order.remainingPaymentSeconds(now);

        order.pay(now);
        validateSaleable(order.getItems());

        PayResultDto result = paymentApi.pay(new PayCommand(order.getId(), order.getOrderNumber(), buyerId,
                order.getTotalPrice(), order.getExpiresAt(), newIdempotencyKey(order)));
        handleFailure(result, remainingSeconds);
        publishPaidEvent(order);
        removeCartItems(buyerId, order.getItems());

        return new OrderPayResponse(order.getId(), order.getOrderNumber(), order.getStatus(),
                order.getTotalPrice(), order.getPaidAt(), result.paymentId());
    }

    // 판매가능여부 확인
    private void validateSaleable(List<OrderItem> items) {
        orderSupport.getSaleableOptions(items.stream().map(OrderItem::getOptionId).toList());
    }

    // 결제 결과가 PAID가 아니면 예외처리, 롤백
    private void handleFailure(PayResultDto result, long remainingSeconds) {
        switch (result.status()) {
            case PAID -> { }
            case FAILED_INSUFFICIENT_BALANCE -> throw new DomainException(OrderErrorCode.INSUFFICIENT_BALANCE,
                    new InsufficientBalanceResponse(result.shortage(), result.chargeAmount(), remainingSeconds));
            case FAILED_ORDER_EXPIRED -> throw new DomainException(OrderErrorCode.ORDER_EXPIRED);
            default -> throw new IllegalStateException("처리할 수 없는 결제 결과입니다: " + result.status());
        }
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

    // 결제 시도마다 새 키 (같은 키로 다시 호출하면 처음 결과를 반환)
    private String newIdempotencyKey(Order order) {
        return "ORDER-" + order.getId() + "-PAY-" + UUID.randomUUID();
    }

    // 장바구니 항목 삭제
    private void removeCartItems(Long buyerId, List<OrderItem> items) {
        List<Long> cartItemIds = items.stream()
                .map(OrderItem::getCartItemId)
                .filter(Objects::nonNull)
                .toList();

        orderSupport.findCart(buyerId).ifPresent(cart -> cart.removeItems(cartItemIds));
    }
}