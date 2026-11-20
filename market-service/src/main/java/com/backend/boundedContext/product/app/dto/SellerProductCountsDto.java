package com.backend.boundedContext.product.app.dto;

/**
 * 판매자 상품 관리 화면의 탭별 개수. 삭제된 상품은 세지 않는다.
 *
 * @param all     전체 (판매중 + 품절 + 판매 중지)
 * @param onSale  판매 중이고 재고 있음
 * @param soldOut 판매 중이지만 재고 0
 * @param stopped 판매 중지
 */
public record SellerProductCountsDto(
        long all,
        long onSale,
        long soldOut,
        long stopped
) {
}
