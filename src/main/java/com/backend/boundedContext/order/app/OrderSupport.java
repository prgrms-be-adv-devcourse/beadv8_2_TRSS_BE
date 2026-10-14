package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Cart;
import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.out.CartRepository;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.out.ProductApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 여러 UseCase가 함께 쓰는 조회.
 */
@Component
@RequiredArgsConstructor
public class OrderSupport {

    private final CartRepository cartRepository;
    private final OrderRepository orderRepository;
    private final ProductApi productApi;

    // 회원 장바구니
    public Optional<Cart> findCart(Long memberId) {
        return cartRepository.findByMemberId(memberId);
    }

    // 회원의 장바구니가 없으면 새로 생성
    public Cart getOrCreateCart(Long memberId) {
        return cartRepository.findByMemberId(memberId)
                .orElseGet(() -> cartRepository.save(Cart.create(memberId)));
    }

    // 결제 대기 주문에 들어 있는 옵션 ID
    public Set<Long> findOptionIdsInPendingOrders(Long buyerId, Collection<Long> optionIds) {
        if (optionIds.isEmpty()) {
            return Set.of();   // 빈 IN 절 방지
        }

        return new HashSet<>(orderRepository.findOptionIdsInOrders(buyerId, optionIds, Order.PAYMENT_PENDING_STATUSES));
    }

    // 주문 생성용: 장바구니를 잠가서 조회
    public Cart getCartForUpdate(Long memberId) {
        return cartRepository.findByMemberIdForUpdate(memberId)
                .orElseThrow(() -> new DomainException(OrderErrorCode.CART_ITEM_NOT_FOUND));
    }

    // 주문 생성·결제용: 옵션이 없거나 판매 불가면 예외처리
    public Map<Long, OptionSnapshotDto> getSaleableOptions(List<Long> optionIds) {
        Map<Long, OptionSnapshotDto> options = productApi.getOptions(optionIds).stream()
                .collect(Collectors.toMap(OptionSnapshotDto::optionId, Function.identity()));

        for (Long optionId : optionIds) {
            OptionSnapshotDto option = options.get(optionId);

            if (option == null) {
                throw new DomainException(OrderErrorCode.PRODUCT_NOT_FOUND);
            }
            if (!option.saleable()) {
                throw new DomainException(OrderErrorCode.PRODUCT_NOT_ON_SALE);
            }
        }

        return options;
    }
}
