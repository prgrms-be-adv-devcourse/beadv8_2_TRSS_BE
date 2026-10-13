package com.backend.boundedContext.product.out;

import com.backend.boundedContext.product.domain.StockChangeType;
import com.backend.boundedContext.product.domain.StockHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface StockHistoryRepository extends JpaRepository<StockHistory, Long> {

    /** 주문 항목 중 이 사유로 이미 이력이 있는 것 (중복 호출 확인용) */
    @Query("select h.orderItemId from StockHistory h where h.orderItemId in :orderItemIds and h.changeType = :changeType")
    List<Long> findOrderItemIds(Collection<Long> orderItemIds, StockChangeType changeType);
}
