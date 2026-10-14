package com.backend.boundedContext.order.domain;

import com.backend.global.exception.DomainException;
import com.backend.global.jpa.entity.BaseIdAndTime;
import com.backend.shared.product.dto.StockChangeDto;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;


/**
 * 주문 항목
 */
@Entity
@Table(name = "market_order_item",
        uniqueConstraints = @UniqueConstraint(name = "uk_market_order_item_option", columnNames = {"order_id", "option_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem extends BaseIdAndTime {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private Long optionId;

    @Column(nullable = false)
    private Long sellerId;

    private Long cartItemId;

    @Column(nullable = false, length = 100)
    private String productName;

    @Column(nullable = false, length = 50)
    private String optionName;

    @Column(nullable = false, length = 100)
    private String sellerName;

    @Column(nullable = false)
    private long unitPrice;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private long lineAmount;

    private Boolean restoreStock;  // 재고 복원여부

    static OrderItem create(Order order, OrderItemSpec spec) {
        if (spec.quantity() < 1) {
            throw new DomainException(OrderErrorCode.ORDER_QUANTITY_INVALID);
        }

        OrderItem item = new OrderItem();
        item.order = order;
        item.productId = spec.productId();
        item.optionId = spec.optionId();
        item.sellerId = spec.sellerId();
        item.cartItemId = spec.cartItemId();
        item.productName = spec.productName();
        item.optionName = spec.optionName();
        item.sellerName = spec.sellerName();
        item.unitPrice = spec.unitPrice();
        item.quantity = spec.quantity();
        item.lineAmount = Math.multiplyExact(spec.unitPrice(), spec.quantity());

        return item;
    }

    // 재고 차감/ 복원 요청으로 변환
    public StockChangeDto toStockChangeDto() {
        return new StockChangeDto(order.getId(), getId(), optionId, quantity);
    }
}