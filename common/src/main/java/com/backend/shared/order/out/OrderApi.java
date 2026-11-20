package com.backend.shared.order.out;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.backend.shared.order.dto.CartLineDto;
import com.backend.shared.order.dto.CartOptionDto;
import com.backend.shared.order.dto.PurchasedProductDto;
import com.backend.shared.order.dto.OrderCancelType;
import com.backend.shared.order.dto.OrderSummaryDto;
import com.backend.shared.order.dto.ReviewableOrderItemDto;

/**
 * 주문 컨텍스트가 다른 컨텍스트에 공개하는 동기 계약
 */
public interface OrderApi {

    // 상품이 들어 있는 결제 대기 주문을 모두 취소하고 재고를 복원한다.
    int cancelPendingOrders(Collection<Long> productIds, OrderCancelType cancelType);

    // 진행 중인 주문이 있는지 확인 (회원 탈퇴 검사). 반품 승인됐는데 환불 전인 주문도 포함한다
    boolean hasActiveOrders(Long buyerId);

    // 판매자 상품이 들어간 진행중인 주문이 있는지 확인
    boolean hasActiveSales(Long sellerId);

    // 구매자의 최근 주문 목록 반환 (없으면 빈 목록)
    List<OrderSummaryDto> getRecentOrders(Long buyerId, int limit);

    // 상품별 paidSince 이후 결제 수량. 결제 뒤 취소·반품된 주문은 빼고 구매확정 전 주문도 센다. 판매가 없는 상품은 키가 없다
    // 상품 컨텍스트가 검색 색인의 판매량(최근 30일)을 계산할 때 쓴다
    Map<Long, Long> getSalesQuantities(Collection<Long> productIds, LocalDateTime paidSince);

    // (from, to]에 결제·취소·반품된 주문의 상품 ID. 상품 컨텍스트가 판매량을 다시 계산할 상품을 찾을 때 쓴다
    List<Long> findProductIdsWithSalesChanged(LocalDateTime from, LocalDateTime to);

    // (from, to]에 결제한(판매량에 세는) 주문의 상품 ID. 상품 컨텍스트가 판매량 집계 구간 밖으로 나가는 판매를 찾을 때 쓴다
    List<Long> findProductIdsPaidBetween(LocalDateTime from, LocalDateTime to);

    // 구매자가 paidSince 이후 결제한 상품 (결제 뒤 취소·반품된 주문 제외), 최근 결제 순으로 최대 limit개. 같은 상품은 하나로 묶는다
    // 상품 컨텍스트가 개인화 추천의 기준 상품을 고를 때 쓴다
    List<PurchasedProductDto> getRecentPurchases(Long buyerId, LocalDateTime paidSince, int limit);

    // 구매자 장바구니에 담긴 옵션 (최근에 담은 순). 장바구니가 없으면 빈 목록
    // 상품 컨텍스트가 개인화 추천의 기준 상품을 고를 때 쓴다
    List<CartOptionDto> getCartOptions(Long buyerId);

    // 구매자 본인의 구매 확정된 주문 항목 조회 (리뷰 작성 자격 확인용)
    Optional<ReviewableOrderItemDto> findConfirmedOrderItem(Long buyerId, Long orderItemId);

    // 내 장바구니 항목들의 현재 상품 금액 합계 (판매 가능한 항목의 현재 가격 × 수량, 배송비 제외)
    // 쿠폰 컨텍스트가 장바구니 쿠폰의 예상 할인액을 계산할 때 쓴다. 내 장바구니 항목이 아니면 404-CART_ITEM_NOT_FOUND
    long getCartItemsAmount(Long buyerId, Collection<Long> cartItemIds);

    // 내 장바구니 항목들의 현재 가격 정보 (항목마다 상품, 단가, 수량, 판매 가능 여부). 보낸 순서대로 주고 중복 ID는 한 번만 준다
    // 쿠폰 컨텍스트가 상품 쿠폰 코드를 검증할 때 쓴다. 내 장바구니 항목이 아니면 404-CART_ITEM_NOT_FOUND
    List<CartLineDto> getCartLines(Long buyerId, Collection<Long> cartItemIds);
}
