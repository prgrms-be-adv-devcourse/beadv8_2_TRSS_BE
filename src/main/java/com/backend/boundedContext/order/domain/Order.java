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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 주문서
 */
@Entity
@Table(name = "market_order")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseIdAndTime {
    private static final String ORDER_NUMBER_PREFIX = "ORD-";
    public static final List<OrderStatus> PAYMENT_PENDING_STATUSES = List.of(OrderStatus.CREATED, OrderStatus.PAYMENT_FAILED);

    @Column(nullable = false, unique = true, length = 40)
    private String orderNumber;

    @Column(nullable = false)
    private Long buyerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderStatus status;

    @Column(nullable = false)
    private long totalPrice;

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
        if (specs == null || specs.isEmpty()) {
            throw new DomainException(OrderErrorCode.ORDER_ITEM_EMPTY);
        }
        validateNoDuplicateOption(specs);

        Order order = new Order();
        order.orderNumber = generateOrderNumber(now);
        order.buyerId = buyerId;
        order.changeStatus(OrderStatus.CREATED, null);
        order.expiresAt = now.plusMinutes(expireMinutes);

        specs.forEach(spec -> order.items.add(OrderItem.create(order, spec)));
        order.totalPrice = order.items.stream()
                .mapToLong(OrderItem::getLineAmount)
                .sum();

        return order;
    }

    // 결제 대기 상태 확인
    public boolean isPaymentPending() {
        return PAYMENT_PENDING_STATUSES.contains(status);
    }

    // 주문 상태가 변경이 될 때마다 이력을 만듬
    // TODO 결제 구현 시 그 경로에서 이력(CREATED·PAYMENT_FAILED → PAID)을 직접 저장해야함
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
}