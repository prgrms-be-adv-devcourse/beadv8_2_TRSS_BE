package com.backend.boundedContext.order.in.dto;

import java.util.List;

/**
 * 409-CART_ITEM_IN_PENDING_ORDER 응답의 data
 * 프론트가 이미 만들어진 결제 대기 주문의 주문서로 이동할 수 있게 주문 ID를 담는다.
 *
 * @param pendingOrderIds 요청한 항목이 들어 있는 결제 대기 주문 ID (오름차순)
 */
public record PendingOrderConflictResponse(
        List<Long> pendingOrderIds
) {
}
