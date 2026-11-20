package com.backend.boundedContext.product.out;

import com.backend.boundedContext.product.domain.OptionStock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

/** 옵션 재고. ID는 옵션 ID다. */
public interface OptionStockRepository extends JpaRepository<OptionStock, Long> {

    /**
     * 재고를 옵션 ID 순으로 쓰기 잠금(SELECT ... FOR UPDATE)한다. 같은 순서로 잠가서 데드락을 막는다.
     * 옵션을 함께 가져오지 않는다 (함께 가져오면 옵션 행까지 쓰기 잠금이 걸려, 옵션 정보 수정과 다시 서로 기다린다).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from OptionStock s where s.id in :optionIds order by s.id")
    List<OptionStock> findAllForUpdateByOptionIdIn(Collection<Long> optionIds);

    /** 옵션 재고를 옵션·상품·카테고리와 함께 한 번에 조회한다 (삭제된 옵션 포함). 재고 행이 없는 옵션은 빠진다. */
    @Query("select s from OptionStock s join fetch s.option o join fetch o.product p join fetch p.category "
            + "where s.id in :optionIds")
    List<OptionStock> findAllWithOptionAndProductByOptionIdIn(Collection<Long> optionIds);

    /** 상품들의 삭제되지 않은 옵션 재고를 옵션과 함께 조회한다 (옵션 ID 순) */
    @Query("select s from OptionStock s join fetch s.option o "
            + "where o.product.id in :productIds and o.deletedAt is null order by o.id")
    List<OptionStock> findAllWithOptionByProductIdIn(Collection<Long> productIds);
}
