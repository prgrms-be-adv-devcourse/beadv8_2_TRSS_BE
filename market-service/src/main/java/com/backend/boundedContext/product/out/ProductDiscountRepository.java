package com.backend.boundedContext.product.out;

import com.backend.boundedContext.product.domain.ProductDiscount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductDiscountRepository extends JpaRepository<ProductDiscount, Long> {

    /** 상품들의 이 시각에 진행 중인 할인. 기간이 겹칠 수 없으므로 상품마다 최대 1개다. */
    @Query("select d from ProductDiscount d where d.product.id in :productIds and d.deletedAt is null "
            + "and d.startsAt <= :now and d.endsAt > :now")
    List<ProductDiscount> findAllActiveByProductIdIn(Collection<Long> productIds, LocalDateTime now);

    /** 상품의 삭제되지 않고 이 시각에 아직 끝나지 않은(진행 중·예약) 할인 (시작 시각 순). 판매자 할인 목록과 기간 겹침 검사에 쓴다 */
    List<ProductDiscount> findAllByProductIdAndDeletedAtIsNullAndEndsAtAfterOrderByStartsAtAsc(Long productId, LocalDateTime now);

    /** 이 시각에 진행 중이거나 예약된(아직 끝나지 않은) 할인이 있는지 */
    boolean existsByProductIdAndDeletedAtIsNullAndEndsAtAfter(Long productId, LocalDateTime now);

    /**
     * 삭제되지 않은 할인 중 (from, to] 사이에 시작했거나 끝난 할인의 상품 ID (할인 경계 재색인용).
     * 시작·종료 시각 부분 인덱스(V13)로 읽는다
     */
    @Query("select distinct d.product.id from ProductDiscount d where d.deletedAt is null "
            + "and ((d.startsAt > :from and d.startsAt <= :to) or (d.endsAt > :from and d.endsAt <= :to))")
    List<Long> findProductIdsByBoundaryBetween(LocalDateTime from, LocalDateTime to);

    /** 상품의 삭제되지 않은 할인 하나 */
    Optional<ProductDiscount> findByIdAndProductIdAndDeletedAtIsNull(Long id, Long productId);
}
