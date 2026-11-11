package com.backend.boundedContext.delivery.domain;

import com.backend.global.jpa.entity.BaseIdAndTime;
import com.backend.shared.order.dto.PaidOrderItemDto;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 배송 항목 (결제 시점 주문 항목 스냅샷)
 * 같은 주문 항목이 두 번 들어가지 않게 order_item_id에 유니크 제약을 둔다(DDL)
 */
@Entity
@Table(name = "delivery_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeliveryItem extends BaseIdAndTime {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_id")
    private Delivery delivery;

    @Column(nullable = false)
    private Long orderItemId;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private Long optionId;

    @Column(nullable = false)
    private Long sellerId;

    @Column(nullable = false, length = 100)
    private String sellerName;

    @Column(nullable = false, length = 100)
    private String productName;

    @Column(nullable = false, length = 50)
    private String optionName;

    @Column(nullable = false)
    private int quantity;

    static DeliveryItem create(Delivery delivery, PaidOrderItemDto dto) {
        DeliveryItem item = new DeliveryItem();
        item.delivery = delivery;
        item.orderItemId = dto.orderItemId();
        item.productId = dto.productId();
        item.optionId = dto.optionId();
        item.sellerId = dto.sellerId();
        item.sellerName = dto.sellerName();
        item.productName = dto.productName();
        item.optionName = dto.optionName();
        item.quantity = dto.quantity();
        return item;
    }
}
