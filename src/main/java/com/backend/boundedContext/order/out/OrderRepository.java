package com.backend.boundedContext.order.out;

import com.backend.boundedContext.order.domain.Order;
import com.backend.shared.order.dto.OrderStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
