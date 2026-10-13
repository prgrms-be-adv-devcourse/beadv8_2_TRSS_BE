package com.backend.boundedContext.product.out;

import com.backend.boundedContext.product.domain.StockHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockHistoryRepository extends JpaRepository<StockHistory, Long> {
}
