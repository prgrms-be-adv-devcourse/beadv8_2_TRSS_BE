package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.boundedContext.order.in.dto.OrderDeliveryResponse;
import com.backend.boundedContext.order.in.dto.OrderDetailResponse;
import com.backend.boundedContext.order.in.dto.OrderItemResponse;
import com.backend.boundedContext.order.in.dto.OrderPaymentResponse;
import com.backend.boundedContext.order.in.dto.RefundStatus;
import com.backend.boundedContext.order.in.dto.ShippingAddressResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.delivery.out.DeliveryApi;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.payment.dto.PaymentAttemptStatus;
import com.backend.shared.payment.out.PaymentApi;
import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.out.ProductApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 주문 상세
 * 배송지가 있고 결제된 주문이면 delivery 서비스에서 배송 정보를 조회해 합친다.
 * delivery가 응답하지 못해도 주문 상세는 실패시키지 않고 deliveryUnavailable = true로 응답한다.
 * 항목마다 상품의 현재 대표 이미지(thumbnailUrl)를 붙인다. 조회에 실패해도 주문 상세는 이미지 없이 응답한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderGetOrderDetailUseCase {

    // 배송 건이 있을 수 있는 상태 (결제 이후). CREATED·PAYING·PAYMENT_FAILED·CANCELED는 배송 건이 없어 부르지 않는다
    private static final Set<OrderStatus> DELIVERY_STATUSES = Set.of(
            OrderStatus.PAID, OrderStatus.CONFIRMED, OrderStatus.RETURN_REQUESTED, OrderStatus.RETURNED);

    private final OrderRepository orderRepository;
    private final PaymentApi paymentApi;
    private final DeliveryApi deliveryApi;
    private final ProductApi productApi;

    public OrderDetailResponse getOrderDetail(Long buyerId, Long orderId) {
        Order order = orderRepository.findWithItemsByIdAndBuyerId(orderId, buyerId)
                .orElseThrow(() -> new DomainException(OrderErrorCode.ORDER_NOT_FOUND));

        // 내 주문인지 확인한 뒤에 결제 내역을 조회한다
        return toDetail(order, LocalDateTime.now(), getPayments(order), getDelivery(order), getThumbnailUrls(order));
    }

    // 결제 내역 조회 결과. unavailable이면 payment 서비스가 응답하지 못한 것
    private record PaymentsResult(List<OrderPaymentResponse> payments, boolean unavailable) {
    }

    private PaymentsResult getPayments(Order order) {
        try {
            return new PaymentsResult(paymentApi.getPaymentAttempts(order.getId()).stream()
                    .map(OrderPaymentResponse::from)
                    .toList(), false);
        } catch (DomainException e) {
            log.warn("결제 내역 조회에 실패해 주문 상세를 결제 내역 없이 응답합니다: orderId={}, reason={}", order.getId(), e.getMessage());
            return new PaymentsResult(List.of(), true);
        }
    }

    // 배송 정보 조회 결과. unavailable이면 delivery 서비스가 응답하지 못한 것
    private record DeliveryResult(OrderDeliveryResponse delivery, boolean unavailable) {
        static final DeliveryResult NONE = new DeliveryResult(null, false);
    }

    private DeliveryResult getDelivery(Order order) {
        if (!order.hasShippingAddress() || !DELIVERY_STATUSES.contains(order.getStatus())) {
            return DeliveryResult.NONE;
        }
        try {
            return deliveryApi.findByOrderId(order.getId())
                    .map(OrderDeliveryResponse::from)
                    .map(delivery -> new DeliveryResult(delivery, false))
                    .orElse(DeliveryResult.NONE);   // 결제 직후 아직 배송 건이 없음 → 프론트는 "배송 준비 중"
        } catch (DomainException e) {
            log.warn("배송 정보 조회에 실패해 주문 상세를 배송 정보 없이 응답합니다: orderId={}, reason={}", order.getId(), e.getMessage());
            return new DeliveryResult(null, true);
        }
    }

    // 옵션 ID → 현재 대표 이미지 URL (주문 목록과 같은 방식). 삭제된 상품도 이미지가 있으면 담긴다
    private Map<Long, String> getThumbnailUrls(Order order) {
        List<Long> optionIds = order.getItems().stream().map(OrderItem::getOptionId).distinct().toList();
        Map<Long, String> thumbnails = new HashMap<>();
        try {
            for (OptionSnapshotDto option : productApi.getOptions(optionIds)) {
                thumbnails.put(option.optionId(), option.thumbnailUrl());
            }
        } catch (RuntimeException e) {
            log.warn("상품 이미지 조회에 실패해 주문 상세를 이미지 없이 응답합니다: orderId={}, reason={}", order.getId(), e.getMessage());
        }
        return thumbnails;
    }

    private OrderDetailResponse toDetail(Order order, LocalDateTime now, PaymentsResult payments,
                                         DeliveryResult delivery, Map<Long, String> thumbnails) {
        return new OrderDetailResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getStatus(),
                order.getTotalPrice(),
                order.getCreatedAt(),
                order.getExpiresAt(),
                order.remainingPaymentSeconds(now),
                order.getPaidAt(),
                order.getCanceledAt(),
                order.getConfirmedAt(),
                order.getItems().stream().map(item -> toItem(item, thumbnails.get(item.getOptionId()))).toList(),
                payments.payments(),
                order.hasShippingAddress() ? ShippingAddressResponse.from(order.getShippingAddress()) : null,
                order.getDeliveredAt(),
                delivery.delivery(),
                delivery.unavailable(),
                order.getProductDiscountAmount(),
                order.getCartCouponDiscountAmount(),
                order.getPayAmount(),
                order.getShippingFee(),
                payments.unavailable(),
                order.getReturnRequestedAt(),
                order.getReturnReasonCode(),
                order.getReturnReason(),
                order.getReturnRejectedAt(),
                order.getReturnRejectReason(),
                order.getReturnedAt(),
                refundStatus(order, payments)
        );
    }

    // 환불 상태: 반품 승인(RETURNED) 주문만 결제 시도의 REFUNDED 여부로 본다
    private RefundStatus refundStatus(Order order, PaymentsResult payments) {
        if (order.getStatus() != OrderStatus.RETURNED) {
            return RefundStatus.NONE;
        }
        if (payments.unavailable()) {
            return RefundStatus.UNKNOWN;
        }
        boolean refunded = payments.payments().stream()
                .anyMatch(payment -> payment.status() == PaymentAttemptStatus.REFUNDED);
        return refunded ? RefundStatus.COMPLETED : RefundStatus.PENDING;
    }

    private OrderItemResponse toItem(OrderItem item, String thumbnailUrl) {
        return new OrderItemResponse(
                item.getId(),
                item.getProductId(),
                item.getOptionId(),
                item.getProductName(),
                item.getOptionName(),
                item.getSellerName(),
                item.getUnitPrice(),
                item.getQuantity(),
                item.getLineAmount(),
                item.getDiscountAmount(),
                item.getListUnitPrice(),
                item.getDiscountRate(),
                thumbnailUrl
        );
    }
}