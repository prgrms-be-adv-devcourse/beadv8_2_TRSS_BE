package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.in.dto.*;
import com.backend.global.page.PageResponse;
import com.backend.shared.order.dto.OrderSummaryDto;
import com.backend.shared.order.dto.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private final OrderConfirmOrderUseCase orderConfirmOrderUseCase;
    private final OrderAutoConfirmOrdersUseCase orderAutoConfirmOrdersUseCase;
    private final OrderExpireOrdersUseCase orderExpireOrdersUseCase;
    private final OrderGetRecentOrdersUseCase orderGetRecentOrdersUseCase;
    private final OrderCheckActiveOrdersUseCase orderCheckActiveOrdersUseCase;

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

    // 구매확정 (수동)
    @Transactional
    public OrderConfirmResponse confirmOrder(Long buyerId, Long orderId) {
        return orderConfirmOrderUseCase.confirm(buyerId, orderId);
    }

    // 자동 구매확정 대상 주문 id 조회 (스케줄러)
    @Transactional(readOnly = true)
    public List<Long> findAutoConfirmTargetIds(LocalDateTime now, Long afterId, int limit) {
        return orderAutoConfirmOrdersUseCase.findTargetIds(now, afterId, limit);
    }

    // 자동 구매확정 한 건 (스케줄러)
    @Transactional
    public boolean autoConfirmOrder(Long orderId, LocalDateTime now) {
        return orderAutoConfirmOrdersUseCase.autoConfirm(orderId, now);
    }

    // 결제 만료 대상 주문 id 조회 (스케줄러)
    @Transactional(readOnly = true)
    public List<Long> findExpireTargetIds(LocalDateTime now, Long afterId, int limit) {
        return orderExpireOrdersUseCase.findTargetIds(now, afterId, limit);
    }

    // 결제 만료 취소 한 건 (스케줄러) - 취소와 재고 복원을 한 트랜잭션으로, 주문마다 따로 연다
    @Transactional
    public boolean expireOrder(Long orderId, LocalDateTime now) {
        return orderExpireOrdersUseCase.expire(orderId, now);
    }

    // 최근 주문 목록 (OrderApi)
    @Transactional(readOnly = true)
    public List<OrderSummaryDto> getRecentOrders(Long buyerId, int limit) {
        return orderGetRecentOrdersUseCase.getRecentOrders(buyerId, limit);
    }

    // 구매자 진행 중 주문 여부 (OrderApi)
    @Transactional(readOnly = true)
    public boolean hasActiveOrders(Long buyerId) {
        return orderCheckActiveOrdersUseCase.hasActiveOrders(buyerId);
    }

    // 판매자 진행 중 판매 여부 (OrderApi)
    @Transactional(readOnly = true)
    public boolean hasActiveSales(Long sellerId) {
        return orderCheckActiveOrdersUseCase.hasActiveSales(sellerId);
    }
}
