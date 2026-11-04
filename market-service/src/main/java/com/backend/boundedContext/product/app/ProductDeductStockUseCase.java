package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.StockChangeType;
import com.backend.boundedContext.product.domain.StockHistory;
import com.backend.boundedContext.product.out.StockHistoryRepository;
import com.backend.shared.product.dto.StockChangeDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ProductDeductStockUseCase {
    private final ProductSupport productSupport;
    private final StockHistoryRepository stockHistoryRepository;

    /**
     * 주문 생성 시 재고를 차감한다. 하나라도 실패하면 예외가 나서 호출한 쪽 트랜잭션 전체가 롤백된다.
     * 이미 차감한 주문 항목은 건너뛴다. 잠근 뒤에 확인해야 같은 요청이 동시에 와도 한 번만 처리된다.
     */
    public void deductStock(List<StockChangeDto> items) {
        if (items.isEmpty()) return;

        Map<Long, ProductOption> options = productSupport.lockOptions(
                items.stream().map(StockChangeDto::optionId).toList());
        Set<Long> deducted = productSupport.findProcessedOrderItemIds(
                items.stream().map(StockChangeDto::orderItemId).toList(), StockChangeType.ORDER_DEDUCT);

        for (StockChangeDto item : items) {
            if (deducted.contains(item.orderItemId())) continue;

            ProductOption option = options.get(item.optionId());
            option.deductStock(item.quantity());   // 판매 불가 409, 재고 부족 400
            stockHistoryRepository.save(StockHistory.ofOrder(
                    option, StockChangeType.ORDER_DEDUCT, -item.quantity(), item.orderId(), item.orderItemId()));
        }
    }
}
