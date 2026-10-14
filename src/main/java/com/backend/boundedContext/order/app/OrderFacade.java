package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.in.dto.*;
import com.backend.global.page.PageResponse;
import com.backend.shared.order.dto.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 주문 컨텍스트 진입점
 */
@Service
@RequiredArgsConstructor
public class OrderFacade {

    private final OrderGetCartUseCase orderGetCartUseCase;
    private final OrderAddCartItemUseCase orderAddCartItemUseCase;
    private final OrderUpdateCartItemUseCase orderUpdateCartItemUseCase;
    private final OrderSelectAllCartItemsUseCase orderSelectAllCartItemsUseCase;
    private final OrderDeleteCartItemsUseCase orderDeleteCartItemsUseCase;
    private final OrderCreateOrderUseCase orderCreateOrderUseCase;
    private final OrderGetOrdersUseCase orderGetOrdersUseCase;
    private final OrderGetOrderDetailUseCase orderGetOrderDetailUseCase;

    @Transactional(readOnly = true)
    public CartResponse getCart(Long memberId) {
        return orderGetCartUseCase.getCart(memberId);
    }

    @Transactional
    public CartItemAddResponse addCartItem(Long memberId, Long optionId, int quantity) {
        return orderAddCartItemUseCase.add(memberId, optionId, quantity);
    }

    @Transactional
    public void updateCartItem(Long memberId, Long itemId, Integer quantity, Boolean selected) {
        orderUpdateCartItemUseCase.update(memberId, itemId, quantity, selected);
    }

    @Transactional
    public void selectAllCartItems(Long memberId, boolean selected) {
        orderSelectAllCartItemsUseCase.selectAll(memberId, selected);
    }

    @Transactional
    public void deleteCartItems(Long memberId, List<Long> itemIds) {
        orderDeleteCartItemsUseCase.delete(memberId, itemIds);
    }

    @Transactional
    public OrderCreateResponse createOrder(Long buyerId, List<Long> cartItemIds, long expectedTotalPrice) {
        return orderCreateOrderUseCase.create(buyerId, cartItemIds, expectedTotalPrice);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryResponse> getOrders(Long buyerId, OrderStatus status, LocalDate from, LocalDate to, Pageable pageable) {
        return orderGetOrdersUseCase.getOrders(buyerId, status, from, to, pageable);
    }

    @Transactional(readOnly = true)
    public OrderDetailResponse getOrderDetail(Long buyerId, Long orderId) {
        return orderGetOrderDetailUseCase.getOrderDetail(buyerId, orderId);
    }
}
