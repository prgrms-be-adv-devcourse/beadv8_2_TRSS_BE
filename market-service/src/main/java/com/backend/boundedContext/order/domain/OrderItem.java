package com.backend.boundedContext.order.domain;

import com.backend.global.exception.DomainException;
import com.backend.global.jpa.entity.BaseIdAndTime;
import com.backend.shared.order.dto.ConfirmedOrderItemDto;
import com.backend.shared.order.dto.PaidOrderItemDto;
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

    // 주문 시점 정가 단가 (상품 가격 + 추가금). 표시용이고 금액 계산은 unitPrice만 쓴다
    @Column(nullable = false)
    private long listUnitPrice;

    // 주문 시점 할인율 (%). 할인 중이 아니었으면 null. 표시용이고 (listUnitPrice - unitPrice)로 다시 계산하지 않는다
    private Integer discountRate;

    // 상품 쿠폰 할인액 (상품 쿠폰이 붙지 않은 항목은 0). 항목 금액을 넘지 않는다
    @Column(nullable = false)
    private long discountAmount;

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
        item.listUnitPrice = spec.listUnitPrice();
        item.discountRate = spec.discountRate();
        item.lineAmount = Math.multiplyExact(spec.unitPrice(), spec.quantity());

        return item;
    }

    // 상품 쿠폰 할인 적용 (주문 생성 때 한 번). 할인액은 0 이상, 항목 금액 이하
    void applyCouponDiscount(long discountAmount) {
        // 정책상 할인 후 금액이 0원이 되는 쿠폰은 없다(쿠폰 쪽이 막는다). 들어오면 코드 오류
        if (discountAmount < 0 || discountAmount >= lineAmount) {
            throw new IllegalArgumentException("상품 쿠폰 할인액이 항목 금액 범위를 벗어났습니다. orderItemId=" + getId()
                    + ", lineAmount=" + lineAmount + ", discountAmount=" + discountAmount);
        }
        this.discountAmount = discountAmount;
    }

    // 반품 승인 때 재고 복원 여부 기록 (Order.approveReturn에서만 부른다)
    void decideRestoreStock(boolean restore) {
        this.restoreStock = restore;
    }

    // 상품 쿠폰 할인 후 항목 금액
    public long getDiscountedLineAmount() {
        return lineAmount - discountAmount;
    }

    // 재고 차감, 복원 요청으로 변환
    public StockChangeDto toStockChangeDto() {
        return new StockChangeDto(order.getId(), getId(), optionId, quantity);
    }

    // 구매 확정 이벤트 항목으로 변환 (정산 후보 생성용)
    public ConfirmedOrderItemDto toConfirmedOrderItemDto() {
        return new ConfirmedOrderItemDto(getId(), productId, sellerId, unitPrice, quantity, lineAmount);
    }

    // 결제 완료 이벤트 항목으로 변환 (배송 피킹 목록용)
    public PaidOrderItemDto toPaidOrderItemDto() {
        return new PaidOrderItemDto(getId(), productId, optionId, sellerId, sellerName, productName, optionName, quantity);
    }
}
