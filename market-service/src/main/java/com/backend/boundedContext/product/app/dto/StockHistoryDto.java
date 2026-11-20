package com.backend.boundedContext.product.app.dto;

import com.backend.boundedContext.product.domain.StockChangeType;
import com.backend.boundedContext.product.domain.StockHistory;

import java.time.LocalDateTime;

/**
 * 재고 이력 한 줄
 *
 * @param changeType INIT(등록) · ADJUST(판매자 조정) · ORDER_DEDUCT(주문) · ORDER_RESTORE(주문 취소) · RETURN_RESTORE(반품)
 * @param changeQty  증감량. 늘면 양수, 줄면 음수
 * @param stockAfter 변경 후 재고
 * @param orderId    주문 때문에 바뀐 경우의 주문 ID. 그 외에는 null
 */
public record StockHistoryDto(
        Long historyId,
        StockChangeType changeType,
        int changeQty,
        int stockAfter,
        Long orderId,
        LocalDateTime createdAt
) {
    public static StockHistoryDto from(StockHistory history) {
        return new StockHistoryDto(history.getId(), history.getChangeType(), history.getChangeQty(),
                history.getStockAfter(), history.getOrderId(), history.getCreatedAt());
    }
}
