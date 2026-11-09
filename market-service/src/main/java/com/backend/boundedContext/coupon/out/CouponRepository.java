package com.backend.boundedContext.coupon.out;

import com.backend.boundedContext.coupon.domain.Coupon;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface CouponRepository extends JpaRepository<Coupon, Long>, JpaSpecificationExecutor<Coupon> {

    /** 쿠폰을 쓰기 잠금해 조회한다 (중지처럼 상태를 바꿀 때 같은 요청이 겹쳐도 한 번만 처리되게) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Coupon c where c.id = :id")
    Optional<Coupon> findByIdForUpdate(Long id);

    /**
     * 수량 제한 쿠폰의 남은 수량을 1 줄인다. 남은 수량이 있을 때만 줄이고, 바뀐 행 수(0 또는 1)를 돌려준다.
     * 0이면 소진된 것이다. 읽고 나서 쓰는 사이에 다른 주문이 끼어들 틈이 없어 초과 사용이 생기지 않는다.
     *
     * <p>영속성 컨텍스트를 비우지 않는다(clearAutomatically = false). 같은 트랜잭션의 주문 엔티티까지 떨어져 나가지 않게 하려는 것으로,
     * 그래서 이미 읽어 둔 Coupon의 remainingQuantity는 예전 값이다. 남은 수량이 필요하면 다시 조회한다.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Coupon c
               set c.remainingQuantity = c.remainingQuantity - 1
             where c.id = :id
               and c.quantityLimited = true
               and c.remainingQuantity > 0
            """)
    int decreaseRemainingQuantity(Long id);

    /** 예약 해제로 남은 수량을 1 되돌린다. 총 수량을 넘지 않게 한다 */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Coupon c
               set c.remainingQuantity = c.remainingQuantity + 1
             where c.id = :id
               and c.quantityLimited = true
               and c.remainingQuantity < c.totalQuantity
            """)
    int increaseRemainingQuantity(Long id);
}
