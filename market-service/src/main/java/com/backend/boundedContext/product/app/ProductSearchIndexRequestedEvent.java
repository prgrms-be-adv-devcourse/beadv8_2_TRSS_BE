package com.backend.boundedContext.product.app;

import java.util.Set;

/**
 * 상품 검색 색인을 다시 맞춰 달라는 요청. 상품·할인·품절 여부가 바뀐 트랜잭션 안에서 발행하고,
 * 커밋된 뒤에 리스너가 DB의 최신 값으로 색인한다 (롤백되면 색인하지 않는다).
 *
 * @param productIds 다시 색인할 상품 ID
 */
public record ProductSearchIndexRequestedEvent(Set<Long> productIds) {
}
