package com.backend.shared.product.dto;

/**
 * 상품 하나의 현재 정보 (옵션이 아니라 상품 단위). 쿠폰 발행과 협약 pick 상품 목록에서 쓴다.
 *
 * @param productId    상품 ID
 * @param sellerId     판매자 ID (회원 ID가 아님)
 * @param name         상품명
 * @param price        현재 가격 (원)
 * @param onSale       구매자에게 팔 수 있는 상태인지 (판매 중이고 카테고리가 사용 중). 재고와는 무관하다
 * @param thumbnailUrl 대표 이미지 URL. 없으면 null
 */
public record ProductSnapshotDto(
        Long productId,
        Long sellerId,
        String name,
        long price,
        boolean onSale,
        String thumbnailUrl
) {
}
