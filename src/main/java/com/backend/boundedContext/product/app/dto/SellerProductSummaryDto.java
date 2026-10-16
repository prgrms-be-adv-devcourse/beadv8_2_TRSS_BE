package com.backend.boundedContext.product.app.dto;

import com.backend.boundedContext.product.domain.ProductDisplayStatus;
import com.backend.boundedContext.product.domain.ProductStoppedBy;

import java.time.LocalDateTime;

/**
 * 판매자 상품 목록의 한 줄
 *
 * @param optionId      재고 조정에 쓰는 옵션 ID (지금은 상품마다 옵션 1개)
 * @param thumbnailUrl  대표 이미지. 이미지 기능 전까지 null
 * @param stock         삭제되지 않은 옵션의 재고 합계
 * @param stoppedBy     판매 중지 주체. 판매 중지가 아니면 null
 * @param stoppedReason 판매 중지 사유. 판매 중지가 아니면 null
 * @param stopRequestPending 판매자가 보낸 판매 중지 요청이 관리자 심사 중이면 true
 */
public record SellerProductSummaryDto(
        Long productId,
        Long optionId,
        String thumbnailUrl,
        String name,
        String categoryName,
        long price,
        int stock,
        ProductDisplayStatus status,
        ProductStoppedBy stoppedBy,
        String stoppedReason,
        boolean stopRequestPending,
        LocalDateTime createdAt
) {
}
