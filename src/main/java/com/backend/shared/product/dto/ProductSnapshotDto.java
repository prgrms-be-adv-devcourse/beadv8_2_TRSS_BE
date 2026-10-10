package com.backend.shared.product.dto;

/**
 * 장바구니·주문에서 쓰는 상품의 현재 정보.
 *
 * <p>saleable은 상품 자체의 판매 가능 여부(ON_SALE이고 카테고리가 사용 중)만 나타낸다.
 * 재고 수량, 본인 상품 여부, 결제 대기 주문 여부는 주문 모듈이 따로 판단한다.
 *
 * @param productId        상품 ID
 * @param sellerId         판매자 ID (회원 ID가 아님)
 * @param name             상품명
 * @param price            현재 가격 (원)
 * @param stock            조회 시점의 재고. 잠그지 않은 값이라 최종 재고 검사는 deductStock이 한다
 * @param status           상품 상태
 * @param saleable         판매 가능 여부
 * @param unsaleableReason 판매 불가 이유. saleable이 true면 null
 * @param thumbnailUrl     대표 이미지 URL. 없으면 null
 */
public record ProductSnapshotDto(
        Long productId,
        Long sellerId,
        String name,
        long price,
        int stock,
        ProductStatus status,
        boolean saleable,
        ProductUnsaleableReason unsaleableReason,
        String thumbnailUrl
) {
}
