package com.backend.boundedContext.order.out;

import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.order.dto.PurchasedProductDto;
import com.backend.shared.order.dto.ReviewableOrderItemDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long>, JpaSpecificationExecutor<OrderItem> {

    // 주문을 함께 가져와서 항목마다 주문을 따로 조회하지 않게 한다 (N+1 방지)
    @Override
    @EntityGraph(attributePaths = "order")
    Page<OrderItem> findAll(Specification<OrderItem> spec, Pageable pageable);

    // 구매자 본인 주문이고 주문 상태가 맞는 항목만 한 번의 쿼리로 조회 (조건이 하나라도 안 맞으면 empty)
    @Query("""
            select new com.backend.shared.order.dto.ReviewableOrderItemDto(
                i.id, i.productId, i.optionId, i.optionName, o.confirmedAt)
            from OrderItem i
            join i.order o
            where i.id = :orderItemId
              and o.buyerId = :buyerId
              and o.status = :status
            """)
    Optional<ReviewableOrderItemDto> findReviewableItem(@Param("buyerId") Long buyerId,
                                                        @Param("orderItemId") Long orderItemId,
                                                        @Param("status") OrderStatus status);

    /** 상품별로 since 이후 결제한 주문(이 상태들)의 수량 합. 판매가 없는 상품은 결과에 없다 */
    @Query("""
            select new com.backend.boundedContext.order.out.ProductSalesQuantity(oi.productId, sum(oi.quantity))
            from OrderItem oi join oi.order o
            where oi.productId in :productIds and o.paidAt >= :since and o.status in :statuses
            group by oi.productId
            """)
    List<ProductSalesQuantity> sumQuantityByProductPaidSince(Collection<Long> productIds, LocalDateTime since,
                                                             Collection<OrderStatus> statuses);

    /** (from, to]에 이 상태들로 바뀐 주문의 상품 ID */
    @Query("""
            select distinct oi.productId from OrderStatusHistory h join h.order o join o.items oi
            where h.createdAt > :from and h.createdAt <= :to and h.toStatus in :statuses
            """)
    List<Long> findProductIdsWithStatusChangedBetween(LocalDateTime from, LocalDateTime to, Collection<OrderStatus> statuses);

    /** (from, to]에 결제한 주문(이 상태들)의 상품 ID */
    @Query("""
            select distinct oi.productId from OrderItem oi join oi.order o
            where o.paidAt > :from and o.paidAt <= :to and o.status in :statuses
            """)
    List<Long> findProductIdsPaidBetween(LocalDateTime from, LocalDateTime to, Collection<OrderStatus> statuses);

    /** 구매자가 since 이후 결제한(이 상태들) 상품. 상품마다 가장 최근 결제 시각으로 묶어 최근 순 */
    @Query("""
            select new com.backend.shared.order.dto.PurchasedProductDto(oi.productId, max(o.paidAt))
            from OrderItem oi join oi.order o
            where o.buyerId = :buyerId and o.paidAt >= :since and o.status in :statuses
            group by oi.productId
            order by max(o.paidAt) desc
            """)
    List<PurchasedProductDto> findRecentPurchases(Long buyerId, LocalDateTime since, Collection<OrderStatus> statuses,
                                                  Limit limit);
}
