package com.backend.boundedContext.product.out;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 상품 검색. 조건에 맞는 상품 ID만 정렬 순서대로 돌려주고, 화면에 쓸 값은 DB에서 다시 읽는다.
 * 지금은 JPA 구현({@link ProductJpaSearchAdapter})이고, ElasticSearch 구현으로 바꿔 끼울 수 있게 ID만 주고받는다.
 */
public interface ProductSearchPort {

    Page<Long> searchProductIds(ProductSearchQuery query, Pageable pageable);
}
