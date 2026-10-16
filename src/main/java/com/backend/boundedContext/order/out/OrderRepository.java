package com.backend.boundedContext.order.out;

import com.backend.boundedContext.order.domain.Order;
import com.backend.shared.order.dto.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {
    @Query("""
            select distinct i.optionId
            from OrderItem i
            where i.order.buyerId = :buyerId
              and i.order.status in :statuses
              and i.optionId in :optionIds
            """)
    List<Long> findOptionIdsInOrders(@Param("buyerId") Long buyerId,
                                     @Param("optionIds") Collection<Long> optionIds,
                                     @Param("statuses") Collection<OrderStatus> statuses);

    // 본인 주문만, 항목까지 한 번에 조회
    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsByIdAndBuyerId(Long id, Long buyerId);

    // 결제,만료 경합 방지-> 주문 행을 잠가서 조회
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id and o.buyerId = :buyerId")
    Optional<Order> findByIdAndBuyerIdForUpdate(@Param("id") Long id, @Param("buyerId") Long buyerId);

    // 구매자의 최근 주문
    List<Order> findByBuyerId(Long buyerId, Pageable pageable);

    // 구매자에게 해당 상태의 주문이 있는지
    boolean existsByBuyerIdAndStatusIn(Long buyerId, Collection<OrderStatus> statuses);

    // 판매자 상품이 들어간 주문이 있는지
    @Query("""
            select count(i) > 0
            from OrderItem i
            where i.sellerId = :sellerId
              and i.order.status in :statuses
            """)
    boolean existsSalesBySellerIdAndStatusIn(@Param("sellerId") Long sellerId,
                                             @Param("statuses") Collection<OrderStatus> statuses);

    // 자동 구매 확정 대상 주문 id
    @Query("""
            select o.id
            from Order o
            where o.status = :status
              and o.paidAt <= :paidBefore
              and o.id > :afterId
            order by o.id
            """)
    List<Long> findAutoConfirmTargetIds(@Param("status") OrderStatus status,
                                        @Param("paidBefore") LocalDateTime paidBefore,
                                        @Param("afterId") Long afterId,
                                        Pageable pageable);

    // 주문 행을 잠가서 조회 (스케줄러용)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    // 결제 만료 처리 대상 주문 id 조회
    @Query("""
            select o.id
            from Order o
            where o.status in :statuses
              and o.expiresAt <= :expiredBefore
              and o.id > :afterId
            order by o.id
            """)
    List<Long> findExpireTargetIds(@Param("statuses") Collection<OrderStatus> statuses,
                                   @Param("expiredBefore") LocalDateTime expiredBefore,
                                   @Param("afterId") Long afterId,
                                   Pageable pageable);

    // 상품이 하나라도 들어 있는 해당 상태의 주문 id 조회
    @Query("""
            select distinct i.order.id
            from OrderItem i
            where i.productId in :productIds
              and i.order.status in :statuses
            """)
    List<Long> findIdsByProductIdsAndStatusIn(@Param("productIds") Collection<Long> productIds,
                                              @Param("statuses") Collection<OrderStatus> statuses);

    // 주문 여러 건을 id 순서대로 잠가서 조회
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id in :ids order by o.id")
    List<Order> findAllByIdInForUpdate(@Param("ids") Collection<Long> ids);
}
