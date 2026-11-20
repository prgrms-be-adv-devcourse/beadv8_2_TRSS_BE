package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.out.CartRepository;
import com.backend.boundedContext.order.out.OrderItemRepository;
import com.backend.shared.order.dto.CartOptionDto;
import com.backend.shared.order.dto.PurchasedProductDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** 구매자가 산 상품과 장바구니에 담은 옵션 (OrderApi, 상품 컨텍스트의 개인화 추천 기준 상품) */
@Service
@RequiredArgsConstructor
public class OrderGetBuyerProductsUseCase {
    private final OrderItemRepository orderItemRepository;
    private final CartRepository cartRepository;

    /** 결제 뒤 취소·반품된 주문은 뺀다 (판매량 집계와 같은 상태) */
    public List<PurchasedProductDto> getRecentPurchases(Long buyerId, LocalDateTime paidSince, int limit) {
        return orderItemRepository.findRecentPurchases(buyerId, paidSince, OrderSalesStatsUseCase.COUNTED, Limit.of(limit));
    }

    public List<CartOptionDto> getCartOptions(Long buyerId) {
        return cartRepository.findOptionsByMemberId(buyerId);
    }
}
