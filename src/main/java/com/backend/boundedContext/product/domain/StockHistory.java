package com.backend.boundedContext.product.domain;

import com.backend.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static jakarta.persistence.FetchType.LAZY;

/**
 * 옵션 재고가 바뀐 기록. 모든 재고 변경은 이력을 남긴다.
 * 같은 주문 항목·사유는 한 번만 기록된다 (중복 차감·복원의 마지막 안전장치).
 * 주문과 관계없는 이력(INIT·ADJUST)은 order_id·order_item_id가 null이다.
 */
@Entity
@Table(name = "stock_history", uniqueConstraints = {
        @UniqueConstraint(name = "uk_stock_history_order_item_change_type", columnNames = {"order_item_id", "change_type"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockHistory extends BaseIdAndTime {

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "option_id", nullable = false)
    private ProductOption option;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StockChangeType changeType;

    /** 변경량. 늘면 양수, 줄면 음수 */
    @Column(nullable = false)
    private int changeQty;

    /** 변경 후 재고 */
    @Column(nullable = false)
    private int stockAfter;

    private Long orderId;

    private Long orderItemId;

    /** 상품 등록 시 초기 재고 이력 */
    public static StockHistory ofInit(ProductOption option) {
        StockHistory history = new StockHistory();
        history.option = option;
        history.changeType = StockChangeType.INIT;
        history.changeQty = option.getStock();
        history.stockAfter = option.getStock();
        return history;
    }

    /** 주문으로 인한 재고 변경 이력. 재고를 바꾼 뒤에 만든다. 차감이면 changeQty가 음수다. */
    public static StockHistory ofOrder(ProductOption option, StockChangeType changeType, int changeQty,
                                       Long orderId, Long orderItemId) {
        StockHistory history = new StockHistory();
        history.option = option;
        history.changeType = changeType;
        history.changeQty = changeQty;
        history.stockAfter = option.getStock();
        history.orderId = orderId;
        history.orderItemId = orderItemId;
        return history;
    }
}
