package com.backend.boundedContext.product.app.dto;

import com.backend.boundedContext.product.domain.ProductStopRequest;
import com.backend.boundedContext.product.domain.ProductStopRequestStatus;

import java.time.LocalDateTime;

/** 판매 중지 요청 */
public record ProductStopRequestDto(
        Long requestId,
        Long productId,
        ProductStopRequestStatus status,
        String reason,
        LocalDateTime createdAt
) {
    public static ProductStopRequestDto from(ProductStopRequest request) {
        return new ProductStopRequestDto(request.getId(), request.getProduct().getId(), request.getStatus(),
                request.getReason(), request.getCreatedAt());
    }
}
