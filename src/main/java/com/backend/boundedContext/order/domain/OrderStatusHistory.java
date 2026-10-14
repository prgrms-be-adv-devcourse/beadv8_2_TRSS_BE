package com.backend.boundedContext.order.domain;

import com.backend.global.jpa.entity.BaseIdAndTime;
import com.backend.shared.order.dto.OrderStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문 상태 이력
 */
@Entity
@Table(name = "order_status_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderStatusHistory extends BaseIdAndTime {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderStatus toStatus;

    // 취소·반품·반품 거절 등 사유 (없으면 null)
    @Column(length = 300)
    private String reason;

    static OrderStatusHistory of(Order order, OrderStatus fromStatus, OrderStatus toStatus, String reason) {
        OrderStatusHistory history = new OrderStatusHistory();
        history.order = order;
        history.fromStatus = fromStatus;
        history.toStatus = toStatus;
        history.reason = reason;

        return history;
    }
}