package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.out.OrderItemRepository;
import com.backend.boundedContext.order.out.ProductSalesQuantity;
import com.backend.shared.order.dto.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 상품 판매량 집계 (상품 검색 색인의 판매량순·행동 점수용, OrderApi).
 * 판매량은 결제한 수량이다. 결제 뒤 취소·반품된 주문은 빼고, 구매확정 전(결제 완료·반품 요청 중)도 센다.
 * 구매확정은 배송 후 7일이 지나야 되므로 확정 기준이면 판매량순이 1~2주 늦어진다.
 */
@Service
@RequiredArgsConstructor
public class OrderSalesStatsUseCase {
    /** 판매량에 세는 주문 상태 */
    static final Set<OrderStatus> COUNTED = Set.of(OrderStatus.PAID, OrderStatus.CONFIRMED, OrderStatus.RETURN_REQUESTED);
    /** 판매량을 바꾸는 상태 변경 (결제되면 늘고, 결제 뒤 취소·반품되면 준다) */
    static final Set<OrderStatus> CHANGING = Set.of(OrderStatus.PAID, OrderStatus.CANCELED, OrderStatus.RETURNED);

    private final OrderItemRepository orderItemRepository;

    /** 상품별 paidSince 이후 결제 수량. 판매가 없는 상품은 키가 없다 */
    public Map<Long, Long> getSalesQuantities(Collection<Long> productIds, LocalDateTime paidSince) {
        if (productIds.isEmpty()) return Map.of();
        return orderItemRepository.sumQuantityByProductPaidSince(productIds, paidSince, COUNTED).stream()
                .collect(Collectors.toMap(ProductSalesQuantity::productId, ProductSalesQuantity::quantity));
    }

    /** (from, to]에 판매량이 바뀌었을 수 있는 상품 ID: 그사이 결제·취소·반품된 주문의 상품 */
    public List<Long> findProductIdsWithSalesChanged(LocalDateTime from, LocalDateTime to) {
        return orderItemRepository.findProductIdsWithStatusChangedBetween(from, to, CHANGING);
    }

    /** (from, to]에 결제한(아직 판매량에 세는) 주문의 상품 ID. 집계 구간 밖으로 나가는 판매를 찾을 때 쓴다 */
    public List<Long> findProductIdsPaidBetween(LocalDateTime from, LocalDateTime to) {
        return orderItemRepository.findProductIdsPaidBetween(from, to, COUNTED);
    }
}
