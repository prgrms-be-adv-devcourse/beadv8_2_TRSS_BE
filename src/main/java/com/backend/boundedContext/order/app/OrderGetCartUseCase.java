package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Cart;
import com.backend.boundedContext.order.domain.CartItem;
import com.backend.boundedContext.order.domain.UnorderableReason;
import com.backend.boundedContext.order.in.dto.CartItemResponse;
import com.backend.boundedContext.order.in.dto.CartResponse;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.out.ProductApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 장바구니 조회
 * 가격·재고를 보장하지 않고, 조회할 때마다 옵션의 현재 정보로 주문 가능 여부와 예상 금액을 다시 계산한다.
 */
@Component
@RequiredArgsConstructor
public class OrderGetCartUseCase {

    private final OrderSupport orderSupport;
    private final ProductApi productApi;
    private final MemberApi memberApi;

    public CartResponse getCart(Long memberId) {
        Optional<Cart> cart = orderSupport.findCart(memberId);

        if (cart.isEmpty() || cart.get().getItems().isEmpty()) {
            return CartResponse.empty();   // 장바구니를 새로 만들지 않는다
        }

        List<CartItem> items = cart.get().getItems();

        // 옵션 정보는 한 번에 조회
        List<Long> optionIds = items.stream().map(CartItem::getOptionId).distinct().toList();

        Map<Long, OptionSnapshotDto> options = productApi.getOptions(optionIds).stream()
                .collect(Collectors.toMap(OptionSnapshotDto::optionId, Function.identity()));

        Long mySellerId = memberApi.findSellerByMemberId(memberId)
                .map(SellerDto::sellerId)
                .orElse(null);

        List<CartItemResponse> itemResponses = items.stream()
                .map(item -> toResponse(item, options.get(item.getOptionId()), mySellerId))
                .toList();

        return summarize(itemResponses);
    }

    private CartItemResponse toResponse(CartItem item, OptionSnapshotDto option, Long mySellerId) {
        boolean selfProduct = option != null && mySellerId != null && Objects.equals(option.sellerId(), mySellerId);
        // TODO 주문 엔티티 구현 후: 결제 대기(CREATED·PAYMENT_FAILED) 주문에 이 옵션이 포함됐는지 확인
        boolean inPendingOrder = false;

        // 주문불가 사유
        UnorderableReason reason = UnorderableReason.resolve(option, item.getQuantity(), selfProduct, inPendingOrder);
        long price = option == null ? 0 : option.price();

        return new CartItemResponse(
                item.getId(),
                item.getOptionId(),
                option == null ? null : option.productId(),
                option == null ? null : option.productName(),
                option == null ? null : option.optionName(),
                option == null ? null : option.thumbnailUrl(),
                price,
                item.getQuantity(),
                price * item.getQuantity(),
                item.isSelected(),
                reason == null,
                reason
        );
    }

    // 장바구니 요약
    private CartResponse summarize(List<CartItemResponse> items) {
        List<CartItemResponse> selected = items.stream().filter(CartItemResponse::selected).toList();

        long expectedTotalPrice = selected.stream()
                .filter(CartItemResponse::orderable)
                .mapToLong(CartItemResponse::lineAmount)
                .sum();

        boolean orderable = !selected.isEmpty() && selected.stream().allMatch(CartItemResponse::orderable);

        return new CartResponse(items, selected.size(), expectedTotalPrice, orderable);
    }
}
