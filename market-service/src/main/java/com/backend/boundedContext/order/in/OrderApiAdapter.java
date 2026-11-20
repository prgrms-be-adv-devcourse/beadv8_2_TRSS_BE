package com.backend.boundedContext.order.in;

import com.backend.shared.order.dto.CartOptionDto;
import com.backend.shared.order.dto.PurchasedProductDto;
import com.backend.shared.order.dto.CartLineDto;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.order.app.OrderFacade;
import com.backend.shared.order.dto.OrderCancelType;
import com.backend.shared.order.dto.OrderSummaryDto;
import com.backend.shared.order.dto.ReviewableOrderItemDto;
import com.backend.shared.order.out.OrderApi;

import lombok.RequiredArgsConstructor;

/**
 * {@link OrderApi} 구현체
 */
@Component
@RequiredArgsConstructor
public class OrderApiAdapter implements OrderApi {

    @Lazy   // 컨텍스트 간 순환 참조 방지 (Order → ProductApi → OrderApi)
    private final OrderFacade orderFacade;

    @Override
    public int cancelPendingOrders(Collection<Long> productIds, OrderCancelType cancelType) {
        return orderFacade.cancelPendingOrders(productIds, cancelType);
    }

    @Override
    public boolean hasActiveOrders(Long buyerId) {
        return orderFacade.hasActiveOrders(buyerId);
    }

    @Override
    public boolean hasActiveSales(Long sellerId) {
        return orderFacade.hasActiveSales(sellerId);
    }

    @Override
    public List<OrderSummaryDto> getRecentOrders(Long buyerId, int limit) {
        return orderFacade.getRecentOrders(buyerId, limit);
    }

    @Override
    public Map<Long, Long> getSalesQuantities(Collection<Long> productIds, LocalDateTime paidSince) {
        return orderFacade.getSalesQuantities(productIds, paidSince);
    }

    @Override
    public List<Long> findProductIdsWithSalesChanged(LocalDateTime from, LocalDateTime to) {
        return orderFacade.findProductIdsWithSalesChanged(from, to);
    }

    @Override
    public List<Long> findProductIdsPaidBetween(LocalDateTime from, LocalDateTime to) {
        return orderFacade.findProductIdsPaidBetween(from, to);
    }

    @Override
    public List<PurchasedProductDto> getRecentPurchases(Long buyerId, LocalDateTime paidSince, int limit) {
        return orderFacade.getRecentPurchases(buyerId, paidSince, limit);
    }

    @Override
    public List<CartOptionDto> getCartOptions(Long buyerId) {
        return orderFacade.getCartOptions(buyerId);
    }

    @Override
    public Optional<ReviewableOrderItemDto> findConfirmedOrderItem(Long buyerId, Long orderItemId) {
        return orderFacade.findConfirmedOrderItem(buyerId, orderItemId);
    }

    @Override
    public long getCartItemsAmount(Long buyerId, Collection<Long> cartItemIds) {
        return orderFacade.getCartItemsAmount(buyerId, cartItemIds);
    }

    @Override
    public List<CartLineDto> getCartLines(Long buyerId, Collection<Long> cartItemIds) {
        return orderFacade.getCartLines(buyerId, cartItemIds);
    }
}
