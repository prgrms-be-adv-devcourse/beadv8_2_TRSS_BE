package com.backend.boundedContext.order.out;

/** 상품 하나의 결제 수량 합 (상품 판매량 집계용) */
public record ProductSalesQuantity(Long productId, long quantity) {
}
