package com.backend.boundedContext.product.out.search;

/**
 * 상품 검색 색인 문서 (ES {@code products} 인덱스). 문서 ID는 상품 ID다.
 * 검색·필터·정렬에 쓰는 값만 담고, 화면 값(정가, 할인율, 이미지 등)은 검색 뒤 DB에서 채운다.
 * 매핑은 {@code resources/elasticsearch/products-index.json}이다.
 *
 * @param productId      상품 ID
 * @param name           상품명 (nori 형태소 검색)
 * @param description    상품 설명 (nori). 비슷한 상품 추천의 텍스트 유사도에 쓴다
 * @param businessName   판매자 상호명 (nori). 검색어로 상호명을 넣어도 그 판매자 상품이 나오게 한다. 판매자 정보가 없으면 null
 * @param categoryId     소분류 ID
 * @param status         판매 상태. 판매 중인 상품만 색인하므로 항상 ON_SALE이다
 * @param soldOut        삭제되지 않은 옵션의 재고가 모두 0인지
 * @param effectivePrice 색인한 시각의 기본가. 할인 중이면 할인가, 아니면 정가 (옵션 추가금 제외). 가격순 정렬에 쓴다
 * @param discountRate   색인한 시각에 진행 중인 할인의 할인율 (%, 소수점 내림). 할인 중이 아니면 0.
 *                       할인은 1% 이상만 등록되므로 0보다 크면 할인 중이다. 특가 필터와 할인율순 정렬에 쓴다
 * @param wishCount      찜 수. 찜 많은순 정렬에 쓴다
 * @param reviewCount    리뷰 수 (삭제 제외)
 * @param ratingScore    별점 항목 (0~1, 베이지안 평균 × 신뢰도, 리뷰가 없으면 0)
 * @param salesCount30d  최근 30일 판매량 (결제 수량, 취소·반품 제외). 판매량순 정렬에 쓴다
 * @param behaviorScore  행동 점수 (0~1, 찜·별점·판매 가중합). 인기순 정렬과 추천에 쓴다
 * @param createdAt      등록 시각 (epoch 밀리초). 최신순 정렬에 쓴다
 */
public record ProductSearchDocument(
        long productId,
        String name,
        String description,
        String businessName,
        long categoryId,
        String status,
        boolean soldOut,
        long effectivePrice,
        int discountRate,
        long wishCount,
        long reviewCount,
        double ratingScore,
        long salesCount30d,
        double behaviorScore,
        long createdAt
) {
    /** 상호명만 바꾼 복사본 (DB 값으로 만든 문서에 트랜잭션 밖에서 상호명을 채울 때) */
    public ProductSearchDocument withBusinessName(String businessName) {
        return new ProductSearchDocument(productId, name, description, businessName, categoryId, status, soldOut,
                effectivePrice, discountRate, wishCount, reviewCount, ratingScore, salesCount30d, behaviorScore, createdAt);
    }
}
