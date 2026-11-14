package com.backend.boundedContext.product.out.search;

/**
 * 상품 검색 색인 문서 (ES {@code products} 인덱스). 문서 ID는 상품 ID다.
 * 검색·필터·정렬에 쓰는 값만 담고, 화면 값(정가, 할인율, 이미지 등)은 검색 뒤 DB에서 채운다.
 * 매핑은 {@code resources/elasticsearch/products-index.json}이다.
 *
 * @param productId      상품 ID
 * @param name           상품명 (nori 형태소 검색)
 * @param categoryId     소분류 ID
 * @param status         판매 상태. 판매 중인 상품만 색인하므로 항상 ON_SALE이다
 * @param soldOut        삭제되지 않은 옵션의 재고가 모두 0인지
 * @param effectivePrice 색인한 시각의 기본가. 할인 중이면 할인가, 아니면 정가 (옵션 추가금 제외). 가격순 정렬에 쓴다
 * @param createdAt      등록 시각 (epoch 밀리초). 최신순 정렬에 쓴다
 */
public record ProductSearchDocument(
        long productId,
        String name,
        long categoryId,
        String status,
        boolean soldOut,
        long effectivePrice,
        long createdAt
) {
}
