package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Cart;
import com.backend.boundedContext.order.domain.CartItem;
import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.domain.OrderPolicy;
import com.backend.boundedContext.order.in.dto.OrderCreateResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.out.ProductApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 주문 생성
 * 장바구니에서 고른 항목으로 결제 대기 주문을 만들고 재고를 차감한다.
 */
@Component
@RequiredArgsConstructor
public class OrderCreateOrderUseCase {

    private final OrderSupport orderSupport;
    private final OrderRepository orderRepository;
    private final OrderPolicy orderPolicy;
    private final ProductApi productApi;
    private final MemberApi memberApi;

    public OrderCreateResponse create(Long buyerId, List<Long> cartItemIds, long expectedTotalPrice) {
        // 탈퇴한 회원은 남은 Access Token으로 주문할 수 없게 401 (재고가 차감되지 않도록)
        memberApi.checkActiveMember(buyerId);

        Cart cart = orderSupport.getCartForUpdate(buyerId);

        // 내 장바구니 항목인지 확인
        List<CartItem> cartItems = cartItemIds.stream()
                .distinct()
                .map(cart::getItem)
                .toList();
        cartItems.forEach(item -> validateQuantity(item.getQuantity()));

        List<Long> optionIds = cartItems.stream().map(CartItem::getOptionId).toList();
        Map<Long, OptionSnapshotDto> options = orderSupport.getSaleableOptions(optionIds);

        validateNotSelfProduct(buyerId, options.values());
        validateNotInPendingOrder(buyerId, optionIds);

        // 주문서 생성
        Map<Long, String> sellerNames = getSellerNames(options.values());
        List<OrderItemSpec> specs = cartItems.stream()
                .map(item -> toSpec(item, options.get(item.getOptionId()), sellerNames))
                .toList();
        Order order = Order.create(buyerId, specs, LocalDateTime.now(), orderPolicy.getExpireMinutes());

        if (order.getTotalPrice() != expectedTotalPrice) {
            throw new DomainException(OrderErrorCode.PRICE_CHANGED);
        }

        orderRepository.save(order);
        productApi.deductStock(order.getItems().stream()
                .map(OrderItem::toStockChangeDto)
                .toList());

        return new OrderCreateResponse(order.getId(), order.getOrderNumber(), order.getTotalPrice(), order.getExpiresAt());
    }

    // 최대 구매 수량
    private void validateQuantity(int quantity) {
        if (quantity > orderPolicy.getCartMaxQuantity()) {
            throw new DomainException(OrderErrorCode.CART_QUANTITY_INVALID);
        }
    }

    // 판매자 본인 상품은 주문할 수 없음
    private void validateNotSelfProduct(Long buyerId, Collection<OptionSnapshotDto> options) {
        Optional<Long> mySellerId = memberApi.findSellerByMemberId(buyerId).map(SellerDto::sellerId);

        if (mySellerId.isEmpty()) {
            return;
        }

        boolean selfProduct = options.stream()
                .anyMatch(option -> mySellerId.get().equals(option.sellerId()));

        if (selfProduct) {
            throw new DomainException(OrderErrorCode.SELF_PURCHASE_NOT_ALLOWED);
        }
    }

    // 결제 대기 주문에 들어 있는 옵션으로는 새 주문을 만들 수 없음
    private void validateNotInPendingOrder(Long buyerId, List<Long> optionIds) {
        if (!orderSupport.findOptionIdsInPendingOrders(buyerId, optionIds).isEmpty()) {
            throw new DomainException(OrderErrorCode.CART_ITEM_IN_PENDING_ORDER);
        }
    }

    // 판매자 정보조회
    private Map<Long, String> getSellerNames(Collection<OptionSnapshotDto> options) {
        List<Long> sellerIds = options.stream().map(OptionSnapshotDto::sellerId).distinct().toList();

        Map<Long, String> sellerNames = memberApi.getSellers(sellerIds).stream()
                .collect(Collectors.toMap(SellerDto::sellerId, SellerDto::businessName));

        // 판매 중인 상품에 판매자가 없으면 데이터 오류
        if (sellerNames.size() != sellerIds.size()) {
            throw new IllegalStateException("판매자 정보가 없는 상품이 있습니다. sellerIds=" + sellerIds);
        }

        return sellerNames;
    }

    private OrderItemSpec toSpec(CartItem item, OptionSnapshotDto option, Map<Long, String> sellerNames) {
        return new OrderItemSpec(
                item.getId(),
                option.productId(),
                option.optionId(),
                option.sellerId(),
                option.productName(),
                option.optionName(),
                sellerNames.get(option.sellerId()),
                option.price(),
                item.getQuantity()
        );
    }
}