package com.backend.shared.order.dto;

// TODO 정산 이벤트를 위해 임시 구현. 추후 수정
public record ConfirmedOrderItemDto(Long orderItemId, Long productId, Long sellerId,
                                    long unitPrice, int quantity, long lineAmount) { }
