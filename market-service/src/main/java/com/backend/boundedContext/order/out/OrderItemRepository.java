package com.backend.boundedContext.order.out;

import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.order.dto.ReviewableOrderItemDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
