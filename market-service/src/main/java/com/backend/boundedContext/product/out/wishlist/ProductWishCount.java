package com.backend.boundedContext.product.out.wishlist;

/** 상품 하나의 찜 수 (상품 검색 색인의 찜 수·행동 점수용) */
public record ProductWishCount(Long productId, long wishCount) {
}
