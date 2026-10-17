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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

        // 상품 옵션 목록 조회
        Map<Long, OptionSnapshotDto> options = productApi.getOptions(optionIds).stream()
                .collect(Collectors.toMap(OptionSnapshotDto::optionId, Function.identity()));

        // 결제 대기 주문에 들어 있는 옵션조회
        Set<Long> pendingOptionIds = orderSupport.findOptionIdsInPendingOrders(memberId, optionIds);

        // 판매자 상호명은 한 번에 조회
        Map<Long, String> sellerNames = getSellerNames(options.values());

        List<CartItemResponse> itemResponses = items.stream()
                .map(item -> {
                    OptionSnapshotDto option = options.get(item.getOptionId());
                    String sellerName = option == null ? null : sellerNames.get(option.sellerId());
                    return toResponse(item, option, sellerName, pendingOptionIds.contains(item.getOptionId()));
                })
                .toList();

        return summarize(itemResponses);
    }

    // 장바구니는 화면 표시용이라 판매자 정보가 없어도 실패시키지 않고 상호명만 비운다 (주문 생성에서 검증)
    private Map<Long, String> getSellerNames(Collection<OptionSnapshotDto> options) {
        List<Long> sellerIds = options.stream().map(OptionSnapshotDto::sellerId).distinct().toList();
        if (sellerIds.isEmpty()) {
            return Map.of();
        }

        return memberApi.getSellers(sellerIds).stream()
                .collect(Collectors.toMap(SellerDto::sellerId, SellerDto::businessName));
    }

    private CartItemResponse toResponse(CartItem item, OptionSnapshotDto option, String sellerName,
                                        boolean inPendingOrder) {
        // 주문불가 사유
        UnorderableReason reason = UnorderableReason.resolve(option, item.getQuantity(), inPendingOrder);
        long price = option == null ? 0 : option.price();

        return new CartItemResponse(
                item.getId(),
                item.getOptionId(),
                option == null ? null : option.productId(),
                sellerName,
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
