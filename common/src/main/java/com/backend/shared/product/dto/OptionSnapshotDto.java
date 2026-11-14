package com.backend.shared.product.dto;

/**
 * 장바구니·주문에서 쓰는 상품 옵션의 현재 정보. 옵션 하나가 한 줄이다.
 *
 * <p>saleable은 상품 ON_SALE이고, 옵션이 삭제되지 않았고, 카테고리가 사용 중인지만 나타낸다.
 * 재고와는 무관하므로 재고는 stock으로 판단한다.
 * 본인 상품 여부, 결제 대기 주문 여부는 주문 모듈이 따로 판단한다.
 *
 * @param optionId         옵션 ID
 * @param productId        상품 ID
 * @param sellerId         판매자 ID (회원 ID가 아님)
 * @param productName      상품명
 * @param optionName       옵션명. 옵션이 하나뿐인 상품은 "기본"
 * @param price            현재 단가 (원). 할인 중이면 할인가 + 추가금, 아니면 정가 + 추가금. 주문 금액·쿠폰·정산은 이 값을 쓴다
 * @param listPrice        정가 단가 (원) = 상품 가격 + 추가금. 할인이 없으면 price와 같다. 화면 표시용(취소선)
 * @param discountRate     할인율 (%, 소수점 내림). 상품 가격(기본가) 기준이라 옵션 추가금과 무관하게 상품 상세의 할인율과 같다.
 *                         할인 중이 아니면 null. 화면 표시용이고 (listPrice - price)로 다시 계산하지 않는다
 * @param stock            조회 시점의 옵션 재고. 잠그지 않은 값이라 최종 재고 검사는 deductStock이 한다
 * @param saleable         판매 가능 여부
 * @param unsaleableReason 판매 불가 이유. saleable이 true면 null.
 *                         여러 개에 해당하면 {@link ProductUnsaleableReason}에 적힌 우선순위로 하나만 준다
 * @param thumbnailUrl     대표 이미지 URL. 없으면 null
 */
public record OptionSnapshotDto(
        Long optionId,
        Long productId,
        Long sellerId,
        String productName,
        String optionName,
        long price,
        long listPrice,
        Integer discountRate,
        int stock,
        boolean saleable,
        ProductUnsaleableReason unsaleableReason,
        String thumbnailUrl
) {
}
