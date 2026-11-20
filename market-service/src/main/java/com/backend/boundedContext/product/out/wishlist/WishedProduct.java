package com.backend.boundedContext.product.out.wishlist;

import java.time.LocalDateTime;

/** 회원이 찜한 상품과 찜한 시각 (개인화 추천 기준 상품) */
public record WishedProduct(Long productId, LocalDateTime wishedAt) {
}
