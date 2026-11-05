package com.backend.boundedContext.product.out;

import com.backend.boundedContext.product.domain.ProductOption;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface ProductOptionRepository extends JpaRepository<ProductOption, Long> {

    /** 옵션을 상품·카테고리와 함께 한 번에 조회한다 (삭제된 옵션 포함) */
    @Query("select o from ProductOption o join fetch o.product p join fetch p.category where o.id in :ids")
    List<ProductOption> findAllWithProductByIdIn(Collection<Long> ids);

    /** 상품들의 삭제되지 않은 옵션 (ID 순) */
    List<ProductOption> findAllByProductIdInAndDeletedAtIsNullOrderByIdAsc(Collection<Long> productIds);

    /** 옵션들이 속한 상품 ID (잠그기 전에 상품부터 잠그려고 쓴다) */
    @Query("select distinct o.product.id from ProductOption o where o.id in :ids")
    List<Long> findProductIdsByIdIn(Collection<Long> ids);

    /**
     * 옵션을 ID 순으로 쓰기 잠금(SELECT ... FOR UPDATE)한다. 같은 순서로 잠가서 데드락을 막는다.
     * 상품·카테고리를 함께 가져오지 않는다 (함께 가져오면 그 행까지 쓰기 잠금이 걸린다).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from ProductOption o where o.id in :ids order by o.id")
    List<ProductOption> findAllForUpdateByIdIn(Collection<Long> ids);
}
