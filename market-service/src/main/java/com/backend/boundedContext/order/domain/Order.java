package com.backend.boundedContext.order.domain;

import com.backend.global.exception.DomainException;
import com.backend.global.jpa.entity.BaseIdAndTime;
import com.backend.shared.order.dto.ConfirmType;
import com.backend.shared.order.dto.OrderCancelType;
import com.backend.shared.order.dto.OrderStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 주문서
 */
@Entity
@Table(name = "market_order",
        indexes = @Index(name = "idx_market_order_buyer", columnList = "buyer_id, created_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseIdAndTime {
    // 주문번호
    private static final String ORDER_NUMBER_PREFIX = "ORD-";
    // 결제 대기 상태
    public static final List<OrderStatus> PAYMENT_PENDING_STATUSES = List.of(OrderStatus.CREATED, OrderStatus.PAYMENT_FAILED);
    // 진행 중 주문 상태
    public static final List<OrderStatus> ACTIVE_STATUSES = List.of(
            OrderStatus.CREATED, OrderStatus.PAYMENT_FAILED, OrderStatus.PAID, OrderStatus.RETURN_REQUESTED);

    @Column(nullable = false, unique = true, length = 40)
    private String orderNumber;

    @Column(nullable = false)
    private Long buyerId;

    // 주문 생성 멱등 키 (키 없이 만든 주문은 null)
    @Column(length = 64)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderStatus status;

    // 상품 금액 합계 (단가 x 수량의 합, 할인 전)
    @Column(nullable = false)
    private long totalPrice;

    // 상품 쿠폰 할인 합계 (항목별 할인의 합)
    @Column(nullable = false)
    private long productDiscountAmount;

    // 장바구니 쿠폰 할인액
    @Column(nullable = false)
    private long cartCouponDiscountAmount;

    // 결제 금액 = 상품 금액 합계 - 상품 쿠폰 할인 - 장바구니 쿠폰 할인
    @Column(nullable = false)
    private long payAmount;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private LocalDateTime paidAt;

    private LocalDateTime canceledAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private OrderCancelType cancelType;

    private LocalDateTime confirmedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ConfirmType confirmType;

    private LocalDateTime returnRequestedAt;

    // TODO 반품 구현 시 사유 코드 enum으로 교체
    @Column(length = 30)
    private String returnReasonCode;

    @Column(length = 300)
    private String returnReason;

    private LocalDateTime returnRejectedAt;

    @Column(length = 300)
    private String returnRejectReason;

    private LocalDateTime returnedAt;

    // 배송지 스냅샷
    @Embedded
    private ShippingAddress shippingAddress;

    // 배송 완료 시각
    private LocalDateTime deliveredAt;

    @Version
    private Long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id asc")
    @BatchSize(size = 100)
    private List<OrderItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id asc")
    private List<OrderStatusHistory> statusHistories = new ArrayList<>();

    // 주문생성
    public static Order create(Long buyerId, List<OrderItemSpec> specs, LocalDateTime now, int expireMinutes) {
        return create(buyerId, null, specs, now, expireMinutes);
    }

    // 주문생성 (멱등 키 포함, 배송지 없음)
    public static Order create(Long buyerId, String idempotencyKey, List<OrderItemSpec> specs, LocalDateTime now, int expireMinutes) {
        return create(buyerId, idempotencyKey, specs, null, now, expireMinutes);
    }

    // 주문생성 (멱등 키·배송지 포함). shippingAddress는 null이면 배송지 없는 주문(결제 기준으로 구매 확정)
    public static Order create(Long buyerId, String idempotencyKey, List<OrderItemSpec> specs, ShippingAddress shippingAddress,
                               LocalDateTime now, int expireMinutes) {
        if (specs == null || specs.isEmpty()) {
            throw new DomainException(OrderErrorCode.ORDER_ITEM_EMPTY);
        }
        validateNoDuplicateOption(specs);

        Order order = new Order();
        order.orderNumber = generateOrderNumber(now);
        order.buyerId = buyerId;
        order.idempotencyKey = idempotencyKey;
        order.shippingAddress = shippingAddress;
        order.changeStatus(OrderStatus.CREATED, null);
        order.expiresAt = now.plusMinutes(expireMinutes);

        specs.forEach(spec -> order.items.add(OrderItem.create(order, spec)));
        order.totalPrice = order.items.stream()
                .mapToLong(OrderItem::getLineAmount)
                .sum();
        order.payAmount = order.totalPrice;   // 할인은 applyProductCouponDiscount, applyCartCouponDiscount로 반영

        return order;
    }

    /**
     * 상품 쿠폰 할인을 항목에 붙인다 (주문 생성 때, 쿠폰을 예약한 뒤). 결제 금액도 다시 계산한다.
     * 한 주문에 상품 쿠폰은 1개라 보통 한 번만 부른다.
     */
    public void applyProductCouponDiscount(Long orderItemId, long discountAmount) {
        OrderItem item = items.stream()
                .filter(it -> Objects.equals(it.getId(), orderItemId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("주문에 없는 항목입니다. orderItemId=" + orderItemId));

        item.applyCouponDiscount(discountAmount);
        this.productDiscountAmount = items.stream().mapToLong(OrderItem::getDiscountAmount).sum();
        recalculatePayAmount();
    }

    // 상품 쿠폰 할인 후 상품 금액 합계 (장바구니 쿠폰 최소 주문 금액의 기준)
    public long getProductSubtotal() {
        return totalPrice - productDiscountAmount;
    }

    /**
     * 장바구니 쿠폰 할인을 반영한다 (주문 생성 때, 상품 쿠폰 다음). 할인은 상품 쿠폰 할인 후 상품 금액 합계를 넘을 수 없다.
     */
    public void applyCartCouponDiscount(long cartCouponDiscountAmount) {
        // 정책상 결제 금액이 0원이 되는 일은 없다(장바구니 쿠폰 할인액 < 최소 주문 금액 <= 상품 금액). 들어오면 코드 오류
        if (cartCouponDiscountAmount < 0 || cartCouponDiscountAmount >= getProductSubtotal()) {
            throw new IllegalArgumentException("장바구니 쿠폰 할인액이 올바르지 않습니다. orderId=" + getId()
                    + ", cartCouponDiscountAmount=" + cartCouponDiscountAmount);
        }
        this.cartCouponDiscountAmount = cartCouponDiscountAmount;
        recalculatePayAmount();
    }

    private void recalculatePayAmount() {
        this.payAmount = totalPrice - productDiscountAmount - cartCouponDiscountAmount;
    }

    // 결제 대기 상태 확인
    public boolean isPaymentPending() {
        return PAYMENT_PENDING_STATUSES.contains(status);
    }

    // 주문 상태가 변경이 될 때마다 이력을 만듬
    private void changeStatus(OrderStatus orderStatus, String reason) {
        statusHistories.add(OrderStatusHistory.of(this, this.status, orderStatus, reason));
        this.status = orderStatus;
    }

    // 한 주문에 같은 옵션은 하나만
    private static void validateNoDuplicateOption(List<OrderItemSpec> specs) {
        Set<Long> optionIds = new HashSet<>();

        for (OrderItemSpec spec : specs) {
            if (!optionIds.add(spec.optionId())) {
                throw new DomainException(OrderErrorCode.ORDER_OPTION_DUPLICATED);
            }
        }
    }

    // 주문번호 생성 ORD-yyyyMMdd-XXXXXXXX
    private static String generateOrderNumber(LocalDateTime now) {
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return ORDER_NUMBER_PREFIX + now.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + random;
    }

    // 결제 가능 남은 시간(초)- 결제 대기가 아니거나 만료됐으면 0
    public long remainingPaymentSeconds(LocalDateTime now) {
        if (!isPaymentPending() || !now.isBefore(expiresAt)) {
            return 0;
        }
        return Duration.between(now, expiresAt).getSeconds();
    }

    // 결제 완료 처리
    public void pay(LocalDateTime now) {
        if (!isPaymentPending()) {
            throw new DomainException(OrderErrorCode.ORDER_STATE_INVALID);
        }
        if (!now.isBefore(expiresAt)) {
            throw new DomainException(OrderErrorCode.ORDER_EXPIRED);
        }

        this.paidAt = now;
        changeStatus(OrderStatus.PAID, null);   // 주문이력도 함께 쌓임
    }

    // 주문 취소
    public void cancel(OrderCancelType cancelType, String reason, LocalDateTime now) {
        if (!isPaymentPending()) {
            throw new DomainException(OrderErrorCode.ORDER_NOT_CANCELABLE);
        }

        this.canceledAt = now;
        this.cancelType = cancelType;
        changeStatus(OrderStatus.CANCELED, reason);   // 주문이력도 함께 쌓임
    }

    // 배송지가 있는 주문인지 (배송 흐름을 타는 주문). 배송지 컬럼이 모두 null이면 JPA가 shippingAddress를 null로 읽는다
    public boolean hasShippingAddress() {
        return shippingAddress != null && shippingAddress.getReceiverName() != null;
    }

    // 구매 확정 - 결제 완료 주문만 가능. 배송지가 있는 주문은 배송 완료 뒤에만 (배송 기능 이전 주문은 배송 데이터가 없어 제외)
    public void confirm(ConfirmType confirmType, LocalDateTime now) {
        if (status != OrderStatus.PAID) {
            throw new DomainException(OrderErrorCode.ORDER_NOT_CONFIRMABLE);
        }
        if (hasShippingAddress() && deliveredAt == null) {
            throw new DomainException(OrderErrorCode.ORDER_NOT_DELIVERED);
        }

        this.confirmedAt = now;
        this.confirmType = confirmType;
        changeStatus(OrderStatus.CONFIRMED, null);   // 주문이력도 함께 쌓임
    }

    // 자동 구매 확정 대상 여부
    public boolean isAutoConfirmable(LocalDateTime now, int autoConfirmDays) {
        if (status != OrderStatus.PAID) {
            return false;
        }
        LocalDateTime base = hasShippingAddress() ? deliveredAt : paidAt;
        return base != null && !now.isBefore(base.plusDays(autoConfirmDays));
    }

    // 배송 완료 기록 (DeliveryCompletedEvent 컨슈머). 같은 배송 완료가 두 번 와도 처음 값을 유지한다
    // 상태와 관계없이 기록한다: 이미 확정된 주문이어도 배송 완료 사실은 남긴다
    public void markDelivered(LocalDateTime deliveredAt) {
        if (this.deliveredAt == null) {
            this.deliveredAt = deliveredAt;
        }
    }

    // 결제 만료 처리 대상 여부 - 결제 대기 상태이고 만료 시각이 지난 대상
    public boolean isExpirable(LocalDateTime now, int graceMinutes) {
        return isPaymentPending()
                && !now.isBefore(expiresAt.plusMinutes(graceMinutes));
    }
}
