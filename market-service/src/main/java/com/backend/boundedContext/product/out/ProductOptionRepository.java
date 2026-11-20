package com.backend.boundedContext.product.out;

import com.backend.boundedContext.product.domain.ProductOption;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductOptionRepository extends JpaRepository<ProductOption, Long> {

    /** 옵션들이 속한 상품 ID (재고를 잠그기 전에 상품부터 잠그려고 쓴다) */
    @Query("select distinct o.product.id from ProductOption o where o.id in :ids")
    List<Long> findProductIdsByIdIn(Collection<Long> ids);

    /**
     * 옵션 수정·삭제용. 옵션 행을 쓰기 잠금하고 읽는다. 다른 트랜잭션이 잡은 행이면 커밋을 기다린 뒤 최신 값을 읽는다.
     * 주문 재고 차감은 옵션 행이 아니라 재고 행(option_stock)만 잠그므로 주문과는 서로 기다리지 않고, 같은 옵션의 수정·삭제끼리만 순서가 정해진다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from ProductOption o where o.id = :optionId and o.product.id = :productId")
    Optional<ProductOption> findByIdAndProductIdForUpdate(@Param("optionId") Long optionId, @Param("productId") Long productId);

    /** 상품의 삭제되지 않은 옵션 (옵션 ID 순) */
    List<ProductOption> findAllByProductIdAndDeletedAtIsNullOrderByIdAsc(Long productId);
}
