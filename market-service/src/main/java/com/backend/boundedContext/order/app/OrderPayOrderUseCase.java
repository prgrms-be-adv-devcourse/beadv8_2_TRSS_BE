package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.app.OrderPaymentSupport.PaymentAttempt;
import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.boundedContext.order.in.dto.InsufficientBalanceResponse;
import com.backend.boundedContext.order.in.dto.OrderPayResponse;
import com.backend.boundedContext.order.out.OrderCouponPort;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.payment.dto.PayCommand;
import com.backend.shared.payment.dto.PayResultDto;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 예치금 결제
 * 결제는 HTTP라 주문 트랜잭션에 참여하지 못하므로 트랜잭션 두 개로 나눈다 (OrderFacade.payOrder는 트랜잭션을 열지 않는다)
 * 1. 상품 판매 가능 여부, 주문 만료 시각, 예약 쿠폰을 지금 쓸 수 있는지 확인
 * 2. 주문을 잠그고 PAYING으로 바꾼 뒤 이번 시도의 멱등키·시작 시각을 기록하고 커밋 (트랜잭션 1)
 * 3. 결제 요청 (트랜잭션 밖)
 * 4. 주문을 다시 잠그고 아직 PAYING이고 같은 키일 때만 결과 반영 (트랜잭션 2, OrderPaymentSupport)
 * 결과를 모르면 "결제 확인 중"(주문 PAYING)으로 응답하고, 재확인 스케줄러가 같은 키로 결제를 다시 불러 정리한다.
 * 결제 금액은 쿠폰 할인을 반영하고 배송비를 더한 payAmount다.
 */
@Component
public class OrderPayOrderUseCase {

    private final OrderRepository orderRepository;
    private final OrderSupport orderSupport;
    private final OrderPaymentSupport orderPaymentSupport;
    private final MemberApi memberApi;
    private final OrderCouponPort orderCouponPort;
    private final TransactionTemplate transaction;

    public OrderPayOrderUseCase(OrderRepository orderRepository, OrderSupport orderSupport,
                                OrderPaymentSupport orderPaymentSupport, MemberApi memberApi,
                                OrderCouponPort orderCouponPort, PlatformTransactionManager transactionManager) {
        this.orderRepository = orderRepository;
        this.orderSupport = orderSupport;
        this.orderPaymentSupport = orderPaymentSupport;
        this.memberApi = memberApi;
        this.orderCouponPort = orderCouponPort;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public OrderPayResponse pay(Long buyerId, Long orderId) {
        // 탈퇴한 회원은 남은 Access Token으로 결제할 수 없게 401
        memberApi.checkActiveMember(buyerId);

        LocalDateTime now = LocalDateTime.now();

        PayCommand command = transaction.execute(status -> startPaying(buyerId, orderId, now));
        PaymentAttempt attempt = orderPaymentSupport.requestAndApply(command, now);

        if (attempt.isUnknown()) {
            // 결과를 모름: 주문은 PAYING. 프론트는 "결제 확인 중"으로 보여 주고 주문 상태를 다시 조회한다
            return new OrderPayResponse(command.orderId(), command.orderNumber(), OrderStatus.PAYING,
                    command.amount(), null, null);
        }
        return toResponse(attempt.order(), attempt.result(), command, now);
    }

    // 1·2단계: 확인하고 PAYING으로. 확인에 실패하면 PAYING 변경도 함께 롤백된다
    private PayCommand startPaying(Long buyerId, Long orderId, LocalDateTime now) {
        Order order = orderRepository.findByIdAndBuyerIdForUpdate(orderId, buyerId)
                .orElseThrow(() -> new DomainException(OrderErrorCode.ORDER_NOT_FOUND));

        // 결제 중이면 409-PAYMENT_IN_PROGRESS, 결제 대기가 아니면 409-ORDER_STATE_INVALID, 만료면 409-ORDER_EXPIRED
        order.startPaying(newIdempotencyKey(order), now);
        validateSaleable(order.getItems());
        // 예약 뒤 중지됐거나 기간이 끝난 쿠폰이면 409-COUPON_NOT_ACTIVE로 돈이 움직이기 전에 멈춘다
        orderCouponPort.validateReservedCouponsUsable(order.getId(), now);

        return orderPaymentSupport.toPayCommand(order);
    }

    // 결과가 PAID가 아니면 예외로 알린다. 주문 상태(PAYMENT_FAILED)는 이미 커밋됐다
    private OrderPayResponse toResponse(Order order, PayResultDto result, PayCommand command, LocalDateTime now) {
        switch (result.status()) {
            case PAID -> {
                if (!order.isPaidWith(command.idempotencyKey())) {
                    // 주문에 반영하지 못해 결제를 취소(보상)했다 (OrderPaymentSupport)
                    throw new DomainException(OrderErrorCode.ORDER_STATE_INVALID);
                }
                return new OrderPayResponse(order.getId(), order.getOrderNumber(), order.getStatus(),
                        order.getPayAmount(), order.getPaidAt(), result.paymentId());
            }
            case FAILED_INSUFFICIENT_BALANCE -> throw new DomainException(OrderErrorCode.INSUFFICIENT_BALANCE,
                    new InsufficientBalanceResponse(result.shortage(), result.chargeAmount(), order.remainingPaymentSeconds(now)));
            case FAILED_ORDER_EXPIRED -> throw new DomainException(OrderErrorCode.ORDER_EXPIRED);
            default -> throw new IllegalStateException("처리할 수 없는 결제 결과입니다: " + result.status());
        }
    }

    // 판매가능여부 확인
    private void validateSaleable(List<OrderItem> items) {
        orderSupport.getSaleableOptions(items.stream().map(OrderItem::getOptionId).toList());
    }

    // 결제 시도마다 새 키 (같은 키로 다시 호출하면 처음 결과를 반환). 재확인 스케줄러는 주문에 저장한 이 키를 다시 쓴다
    private String newIdempotencyKey(Order order) {
        return "ORDER-" + order.getId() + "-PAY-" + UUID.randomUUID();
    }
}
