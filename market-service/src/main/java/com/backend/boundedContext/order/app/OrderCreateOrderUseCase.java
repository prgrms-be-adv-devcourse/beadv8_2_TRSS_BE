package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Cart;
import com.backend.boundedContext.order.domain.CartItem;
import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.domain.OrderPolicy;
import com.backend.boundedContext.order.domain.OrderCouponPolicy;
import com.backend.boundedContext.order.in.dto.OrderCreateResponse;
import com.backend.boundedContext.order.in.dto.PendingOrderConflictResponse;
import com.backend.boundedContext.order.in.dto.PriceChangedResponse;
import com.backend.boundedContext.order.in.dto.ShippingAddressRequest;
import com.backend.boundedContext.order.out.OrderCouponPort;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.out.ProductApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 *
 * <p>쿠폰(OrderCouponPolicy가 켜져 있을 때만)은 재고 차감과 같은 트랜잭션에서 아래 순서로 반영한다.
 * 쿠폰 예약이 실패하면 주문 저장과 재고 차감까지 함께 롤백된다.
 * <ol>
 *     <li>상품 금액 = 단가 x 수량</li>
 *     <li>상품 쿠폰 할인 (지정한 항목 1개에 예약, 할인액은 그 항목 금액 이하)</li>
 *     <li>배송비 (상품 쿠폰 할인 후 상품 금액 합계 기준, OrderPolicy.shippingFeeFor). 쿠폰이 없어도 반영한다</li>
 *     <li>장바구니 쿠폰 할인 (상품 쿠폰 할인 후 상품 금액 합계 기준. 배송비와 무관)</li>
 *     <li>결제 금액 = 상품 금액 합계 - 상품 쿠폰 할인 - 장바구니 쿠폰 할인 + 배송비</li>
 * </ol>
 * 예상 결제 금액(expectedTotalPrice)은 배송비를 뺀 금액과 비교한다. 장바구니의 expectedTotalPrice도 배송비를 빼고 보여 주기 때문이다.
 * 잠금 순서는 장바구니 → 상품과 옵션(재고 차감) → 쿠폰이다. 쿠폰 쪽 작업(중지, 자동 중지)은 쿠폰보다 먼저 상품을 잠그지 않으므로
 * 서로 기다리다 막히지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreateOrderUseCase {

    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 64;

    private final OrderSupport orderSupport;
    private final OrderRepository orderRepository;
    private final OrderPolicy orderPolicy;
    private final ProductApi productApi;
    private final MemberApi memberApi;
    private final OrderCouponPolicy orderCouponPolicy;
    private final OrderCouponPort orderCouponPort;

    // 쿠폰 없이 주문
    public OrderCreateResponse create(Long buyerId, String idempotencyKey, List<Long> cartItemIds, long expectedTotalPrice,
                                      ShippingAddressRequest shippingAddress) {
        return create(buyerId, idempotencyKey, cartItemIds, expectedTotalPrice, shippingAddress, OrderCouponSelection.NONE);
    }

    // shippingAddress: null이면 배송지 없는 주문 (프론트가 붙기 전까지 선택 값)
    public OrderCreateResponse create(Long buyerId, String idempotencyKey, List<Long> cartItemIds, long expectedTotalPrice,
                                      ShippingAddressRequest shippingAddress, OrderCouponSelection coupons) {
        // 탈퇴한 회원은 남은 Access Token으로 주문할 수 없게 401 (재고가 차감되지 않도록)
        memberApi.checkActiveMember(buyerId);
        validateIdempotencyKey(idempotencyKey);

        Cart cart = orderSupport.getCartForUpdate(buyerId);

        // 같은 키로 다시 온 요청이면 처음 만든 주문을 그대로 돌려준다
        // 그 주문이 취소(만료 포함)됐으면 키를 다시 쓸 수 없으므로 409로 알려 새 키로 주문하게 한다
        // 배송지도 처음 주문의 것을 그대로 둔다(다시 보낸 배송지와 비교·변경하지 않는다)
        if (idempotencyKey != null) {
            Optional<Order> existing = orderRepository.findByBuyerIdAndIdempotencyKey(buyerId, idempotencyKey);
            if (existing.isPresent()) {
                if (existing.get().getStatus() == OrderStatus.CANCELED) {
                    throw new DomainException(OrderErrorCode.IDEMPOTENCY_KEY_EXPIRED);
                }
                return OrderCreateResponse.from(existing.get());
            }
        }

        // 새 주문을 만들 때만 쿠폰 설정을 본다. 같은 키의 재시도는 위에서 처음 만든 주문을 돌려주므로,
        // 쿠폰을 쓴 주문을 만든 뒤 설정이 꺼져도 재시도가 409로 막히지 않는다
        validateCouponsAvailable(coupons);

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
        Order order = Order.create(buyerId, idempotencyKey, specs,
                shippingAddress == null ? null : shippingAddress.toShippingAddress(),
                LocalDateTime.now(), orderPolicy.getExpireMinutes());

        // 쿠폰이 없으면 배송비를 뺀 결제 금액 = 상품 금액 합계라 저장·재고 차감 전에 바로 확인한다
        if (coupons.isEmpty() && order.getTotalPrice() != expectedTotalPrice) {
            throw new DomainException(OrderErrorCode.PRICE_CHANGED, new PriceChangedResponse(order.getTotalPrice()));
        }

        orderRepository.save(order);
        productApi.deductStock(order.getItems().stream()
                .map(OrderItem::toStockChangeDto)
                .toList());

        applyCouponsAndShippingFee(buyerId, order, coupons, LocalDateTime.now());

        // 쿠폰을 쓰면 예상 결제 금액을 쿠폰 할인 후, 배송비를 뺀 금액과 비교한다. 할인은 쿠폰을 예약해야 정해지므로 여기서 본다.
        // 다르면 같은 트랜잭션의 주문 저장, 재고 차감, 쿠폰 예약이 모두 롤백된다
        if (order.getPayAmountBeforeShipping() != expectedTotalPrice) {
            throw new DomainException(OrderErrorCode.PRICE_CHANGED, new PriceChangedResponse(order.getPayAmountBeforeShipping()));
        }

        // 금액 항목만 남긴다 (배송지·연락처는 남기지 않는다)
        log.info("주문 생성 orderId={}, totalPrice={}, productDiscountAmount={}, cartCouponDiscountAmount={}, shippingFee={}, payAmount={}",
                order.getId(), order.getTotalPrice(), order.getProductDiscountAmount(), order.getCartCouponDiscountAmount(),
                order.getShippingFee(), order.getPayAmount());

        return OrderCreateResponse.from(order);
    }

    // 쿠폰 적용이 꺼져 있을 때 쿠폰을 보내면 409 (할인 없이 결제되는 일이 없게, 재고를 건드리기 전에 막는다)
    private void validateCouponsAvailable(OrderCouponSelection coupons) {
        if (!coupons.isEmpty() && !orderCouponPolicy.isCouponEnabled()) {
            throw new DomainException(OrderErrorCode.ORDER_COUPON_NOT_AVAILABLE);
        }
    }

    /**
     * 상품 쿠폰 → 배송비 → 장바구니 쿠폰 순서로 반영한다. 쿠폰 예약은 주문 ID와 주문 항목 ID가 필요해 주문을 저장한 뒤에 한다.
     * 쿠폰이 없으면 배송비만 반영해 결제 금액은 상품 금액 합계 + 배송비다.
     */
    private void applyCouponsAndShippingFee(Long buyerId, Order order, OrderCouponSelection coupons, LocalDateTime now) {
        for (OrderCouponSelection.ProductCoupon selection : coupons.productCoupons()) {
            OrderItem target = order.getItems().stream()
                    .filter(item -> selection.cartItemId().equals(item.getCartItemId()))
                    .findFirst()
                    .orElseThrow(() -> new DomainException(OrderErrorCode.CART_ITEM_NOT_FOUND));

            long discount = orderCouponPort.reserveProductCoupon(buyerId, selection.code(), order.getId(), target.getId(),
                    target.getProductId(), target.getLineAmount(), now);
            order.applyProductCouponDiscount(target.getId(), discount);
        }

        // 배송비는 상품 쿠폰 할인 뒤 금액으로 정한다 (장바구니 쿠폰은 기준에 영향 없음)
        order.applyShippingFee(orderPolicy.shippingFeeFor(order.getProductSubtotal()));

        if (coupons.cartCouponId() != null) {
            long cartCouponDiscount = orderCouponPort.reserveCartCoupon(buyerId, coupons.cartCouponId(), order.getId(),
                    order.getProductSubtotal(), now);
            order.applyCartCouponDiscount(cartCouponDiscount);
        }
    }

    // 키를 보냈다면 비어 있지 않고 컬럼 길이 이내여야 함
    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey != null
                && (idempotencyKey.isBlank() || idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH)) {
            throw new DomainException(OrderErrorCode.IDEMPOTENCY_KEY_INVALID);
        }
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
    // 응답 유실 후 키 없이 다시 보낸 경우에도 주문서로 돌아갈 수 있게 결제 대기 주문 ID를 담는다
    private void validateNotInPendingOrder(Long buyerId, List<Long> optionIds) {
        List<Long> pendingOrderIds = orderRepository.findOrderIdsContainingOptions(
                buyerId, optionIds, Order.PAYMENT_PENDING_STATUSES);

        if (!pendingOrderIds.isEmpty()) {
            throw new DomainException(OrderErrorCode.CART_ITEM_IN_PENDING_ORDER,
                    new PendingOrderConflictResponse(pendingOrderIds));
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