package com.backend.boundedContext.order.out;

import com.backend.boundedContext.order.domain.OrderItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long>, JpaSpecificationExecutor<OrderItem> {

    // 주문을 함께 가져와서 항목마다 주문을 따로 조회하지 않게 한다 (N+1 방지)
    @Override
    @EntityGraph(attributePaths = "order")
    Page<OrderItem> findAll(Specification<OrderItem> spec, Pageable pageable);
}
