package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.StockChangeType;
import com.backend.boundedContext.product.domain.StockHistory;
import com.backend.boundedContext.product.out.StockHistoryRepository;
import com.backend.shared.product.dto.StockChangeDto;
import com.backend.shared.product.dto.StockRestoreReason;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ProductRestoreStockUseCase {
    private final ProductSupport productSupport;
    private final StockHistoryRepository stockHistoryRepository;

    /**
     * 주문 취소·만료·반품으로 재고를 되돌린다.
     * 같은 주문 항목·사유로 이미 복원했으면 건너뛰고, 차감한 적 없는 항목이면 호출 순서 오류다.
     */
    public void restoreStock(List<StockChangeDto> items, StockRestoreReason reason) {
        if (items.isEmpty()) return;

        StockChangeType changeType = toChangeType(reason);
        List<Long> orderItemIds = items.stream().map(StockChangeDto::orderItemId).toList();

        Map<Long, ProductOption> options = productSupport.lockOptions(
                items.stream().map(StockChangeDto::optionId).toList());
        Set<Long> restored = productSupport.findProcessedOrderItemIds(orderItemIds, changeType);
        Set<Long> deducted = productSupport.findProcessedOrderItemIds(orderItemIds, StockChangeType.ORDER_DEDUCT);

        for (StockChangeDto item : items) {
            if (restored.contains(item.orderItemId())) continue;
            if (!deducted.contains(item.orderItemId())) {
                throw new IllegalStateException("차감한 적 없는 주문 항목은 복원할 수 없습니다. orderItemId=" + item.orderItemId());
            }

            ProductOption option = options.get(item.optionId());
            option.restoreStock(item.quantity(), changeType);   // 반품 복원인데 삭제된 상품·옵션이면 409
            stockHistoryRepository.save(StockHistory.ofOrder(
                    option, changeType, item.quantity(), item.orderId(), item.orderItemId()));
        }
    }

    private StockChangeType toChangeType(StockRestoreReason reason) {
        return switch (reason) {
            case ORDER_RESTORE -> StockChangeType.ORDER_RESTORE;
            case RETURN_RESTORE -> StockChangeType.RETURN_RESTORE;
        };
    }
}
