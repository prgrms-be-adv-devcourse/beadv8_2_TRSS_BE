package com.backend.boundedContext.order.domain;

/**
 * 반품 승인 때 주문 항목 하나의 재고 복원 결정
 *
 * @param orderItemId  주문 항목 id
 * @param restoreStock true면 수량 전체를 재고로 되돌린다(RETURN_RESTORE)
 */
public record ReturnItemDecision(Long orderItemId, boolean restoreStock) {
}
