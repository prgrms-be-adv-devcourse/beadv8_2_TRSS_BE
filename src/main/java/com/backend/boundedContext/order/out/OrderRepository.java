package com.backend.boundedContext.order.out;

import com.backend.boundedContext.order.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
}