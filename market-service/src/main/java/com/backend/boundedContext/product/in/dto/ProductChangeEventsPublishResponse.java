package com.backend.boundedContext.product.in.dto;

/**
 * 상품 변경 이벤트 전체 재발행 결과
 *
 * @param publishedCount 이벤트를 발행한 판매 중 상품 수
 */
public record ProductChangeEventsPublishResponse(int publishedCount) {
}
