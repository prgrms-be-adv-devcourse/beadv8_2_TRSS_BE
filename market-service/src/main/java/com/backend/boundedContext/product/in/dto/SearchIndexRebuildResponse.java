package com.backend.boundedContext.product.in.dto;

/**
 * 상품 검색 전체 재색인 결과
 *
 * @param indexedCount 색인한 판매 중 상품 수
 */
public record SearchIndexRebuildResponse(int indexedCount) {
}
