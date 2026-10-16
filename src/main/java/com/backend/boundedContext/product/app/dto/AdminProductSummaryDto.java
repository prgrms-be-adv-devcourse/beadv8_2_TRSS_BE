package com.backend.boundedContext.product.app.dto;

import com.backend.boundedContext.product.domain.ProductSaleStatus;
import com.backend.boundedContext.product.domain.ProductStoppedBy;

import java.time.LocalDateTime;

/**
 * 관리자 상품 목록의 한 줄
 *
 * @param stock         삭제되지 않은 옵션의 재고 합계
 * @param soldOut       판매 중이면서 재고가 0이면 true
 * @param stoppedBy     판매 중지 주체. 판매 중지가 아니면 null
 * @param stoppedReason 판매 중지 사유. 판매 중지가 아니면 null
 * @param stoppedAt     판매 중지 시각. 판매 중지가 아니면 null
 */
public record AdminProductSummaryDto(
        Long productId,
        Long sellerId,
        String thumbnailUrl,
        String name,
        String categoryName,
        long price,
        int stock,
        ProductSaleStatus status,
        boolean soldOut,
        ProductStoppedBy stoppedBy,
        String stoppedReason,
        LocalDateTime stoppedAt,
        LocalDateTime createdAt
) {
}
