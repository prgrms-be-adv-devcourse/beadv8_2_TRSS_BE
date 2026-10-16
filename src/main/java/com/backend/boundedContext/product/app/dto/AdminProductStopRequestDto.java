package com.backend.boundedContext.product.app.dto;

import com.backend.boundedContext.product.domain.ProductStopRequest;
import com.backend.boundedContext.product.domain.ProductStopRequestStatus;

import java.time.LocalDateTime;

/**
 * 관리자가 보는 판매 중지 요청
 *
 * @param rejectReason 반려 사유. 반려가 아니면 null
 * @param reviewedAt   승인·반려했거나 자동으로 닫힌 시각. 심사 중이면 null
 */
public record AdminProductStopRequestDto(
        Long requestId,
        Long productId,
        String productName,
        Long sellerId,
        String reason,
        ProductStopRequestStatus status,
        String rejectReason,
        LocalDateTime createdAt,
        LocalDateTime reviewedAt
) {
    public static AdminProductStopRequestDto from(ProductStopRequest request) {
        return new AdminProductStopRequestDto(
                request.getId(), request.getProduct().getId(), request.getProduct().getName(), request.getSellerId(),
                request.getReason(), request.getStatus(), request.getRejectReason(),
                request.getCreatedAt(), request.getReviewedAt());
    }
}
