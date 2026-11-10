package com.backend.shared.order.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.backend.shared.order.dto.OrderCancelType;
import com.backend.shared.order.dto.OrderSummaryDto;
import com.backend.shared.order.dto.ReviewableOrderItemDto;

/**
 * 주문 컨텍스트가 다른 컨텍스트에 공개하는 동기 계약
 */
public interface OrderApi {

    // 상품이 들어 있는 결제 대기 주문을 모두 취소하고 재고를 복원한다.
    int cancelPendingOrders(Collection<Long> productIds, OrderCancelType cancelType);

    // 진행 중인 주문이 있는지 확인
    boolean hasActiveOrders(Long buyerId);

    // 판매자 상품이 들어간 진행중인 주문이 있는지 확인
    boolean hasActiveSales(Long sellerId);

    // 구매자의 최근 주문 목록 반환 (없으면 빈 목록)
    List<OrderSummaryDto> getRecentOrders(Long buyerId, int limit);

    // 구매자 본인의 구매 확정된 주문 항목 조회 (리뷰 작성 자격 확인용)
    Optional<ReviewableOrderItemDto> findConfirmedOrderItem(Long buyerId, Long orderItemId);

    // 내 장바구니 항목들의 현재 상품 금액 합계 (판매 가능한 항목의 현재 가격 × 수량, 배송비 제외)
    // 쿠폰 컨텍스트가 장바구니 쿠폰의 예상 할인액을 계산할 때 쓴다. 내 장바구니 항목이 아니면 404-CART_ITEM_NOT_FOUND
    long getCartItemsAmount(Long buyerId, Collection<Long> cartItemIds);
}
