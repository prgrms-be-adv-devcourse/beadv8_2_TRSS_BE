package com.backend.shared.order.dto;

// 결제된 주문 항목 (배송 피킹 목록용)
public record PaidOrderItemDto(Long orderItemId, Long productId, Long optionId, Long sellerId, String sellerName,
                               String productName, String optionName, int quantity) { }
