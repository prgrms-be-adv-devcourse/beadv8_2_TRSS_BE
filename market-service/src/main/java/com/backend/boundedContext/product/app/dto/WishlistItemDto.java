package com.backend.boundedContext.product.app.dto;

import java.time.LocalDateTime;

/**
 * 내 찜 목록 한 줄
 *
 * @param thumbnailUrl 대표 이미지. 없으면 null
 * @param soldOut      삭제되지 않은 옵션의 재고가 모두 0이면 true
 * @param available    구매자에게 보이는 상품이면 true. 판매 중지·삭제됐거나 카테고리가 사용 중지면 false (목록에는 남긴다)
 * @param wishedAt     찜한 시각
 */
public record WishlistItemDto(
        Long productId,
        String name,
        long price,
        String thumbnailUrl,
        boolean soldOut,
        boolean available,
        LocalDateTime wishedAt
) {
}
