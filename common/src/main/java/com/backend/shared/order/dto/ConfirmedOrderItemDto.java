package com.backend.shared.order.dto;

// 구매확정 주문
public record ConfirmedOrderItemDto(Long orderItemId, Long productId, Long sellerId,
                                    long unitPrice, int quantity, long lineAmount) { }
