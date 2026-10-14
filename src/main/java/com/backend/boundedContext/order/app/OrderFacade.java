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
    private final OrderPayOrderUseCase orderPayOrderUseCase;
    private final OrderCancelOrderUseCase orderCancelOrderUseCase;

    // 장바구니 조회
    @Transactional(readOnly = true)
    public CartResponse getCart(Long memberId) {
        return orderGetCartUseCase.getCart(memberId);
    }

    // 장바구니 담기
    @Transactional
    public CartItemAddResponse addCartItem(Long memberId, Long optionId, int quantity) {
        return orderAddCartItemUseCase.add(memberId, optionId, quantity);
    }

    // 장바구니 수량변경
    @Transactional
    public void updateCartItem(Long memberId, Long itemId, Integer quantity, Boolean selected) {
        orderUpdateCartItemUseCase.update(memberId, itemId, quantity, selected);
    }

    // 장바구니 전체선택,해제
    @Transactional
    public void selectAllCartItems(Long memberId, boolean selected) {
        orderSelectAllCartItemsUseCase.selectAll(memberId, selected);
    }

    // 장바구니 항목삭제
    @Transactional
    public void deleteCartItems(Long memberId, List<Long> itemIds) {
        orderDeleteCartItemsUseCase.delete(memberId, itemIds);
    }

    // 주문생성
    @Transactional
    public OrderCreateResponse createOrder(Long buyerId, List<Long> cartItemIds, long expectedTotalPrice) {
        return orderCreateOrderUseCase.create(buyerId, cartItemIds, expectedTotalPrice);
    }

    // 주문목록 조회
    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryResponse> getOrders(Long buyerId, OrderStatus status, LocalDate from, LocalDate to, Pageable pageable) {
        return orderGetOrdersUseCase.getOrders(buyerId, status, from, to, pageable);
    }

    // 주문 상세조회
    @Transactional(readOnly = true)
    public OrderDetailResponse getOrderDetail(Long buyerId, Long orderId) {
        return orderGetOrderDetailUseCase.getOrderDetail(buyerId, orderId);
    }

    // 결제요청
    @Transactional
    public OrderPayResponse payOrder(Long buyerId, Long orderId) {
        return orderPayOrderUseCase.pay(buyerId, orderId);
    }

    // 주문취소
    @Transactional
    public OrderCancelResponse cancelOrder(Long buyerId, Long orderId) {
        return orderCancelOrderUseCase.cancel(buyerId, orderId);
    }
}
