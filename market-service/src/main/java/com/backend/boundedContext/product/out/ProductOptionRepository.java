package com.backend.boundedContext.product.out;

import com.backend.boundedContext.product.domain.ProductOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface ProductOptionRepository extends JpaRepository<ProductOption, Long> {

    /** 상품들의 삭제되지 않은 옵션 (ID 순) */
    List<ProductOption> findAllByProductIdInAndDeletedAtIsNullOrderByIdAsc(Collection<Long> productIds);

    /** 옵션들이 속한 상품 ID (재고를 잠그기 전에 상품부터 잠그려고 쓴다) */
    @Query("select distinct o.product.id from ProductOption o where o.id in :ids")
    List<Long> findProductIdsByIdIn(Collection<Long> ids);
}
