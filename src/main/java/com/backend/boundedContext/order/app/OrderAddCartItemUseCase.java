package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Cart;
import com.backend.boundedContext.order.domain.CartItem;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.domain.OrderPolicy;
import com.backend.boundedContext.order.in.dto.CartItemAddResponse;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.out.ProductApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 장바구니 담기
 */
@Component
@RequiredArgsConstructor
public class OrderAddCartItemUseCase {

    private final OrderSupport orderSupport;
    private final OrderPolicy orderPolicy;
    private final ProductApi productApi;
    private final MemberApi memberApi;

    public CartItemAddResponse add(Long memberId, Long optionId, int quantity) {
        OptionSnapshotDto option = productApi.getOptions(List.of(optionId)).stream()
                .findFirst()
                .orElseThrow(() -> new DomainException(OrderErrorCode.PRODUCT_NOT_FOUND));

        // 판매가능여부 확인
        if (!option.saleable()) {
            throw new DomainException(OrderErrorCode.PRODUCT_NOT_ON_SALE);
        }

        // 판매자 본인 상품은 담을 수 없음
        boolean selfProduct = memberApi.findSellerByMemberId(memberId)
                .filter(seller -> seller.sellerId().equals(option.sellerId()))
                .isPresent();

        if (selfProduct) {
            throw new DomainException(OrderErrorCode.SELF_PURCHASE_NOT_ALLOWED);
        }

        Cart cart = orderSupport.getOrCreateCart(memberId);

        // 합산 후 수량이 옵션 재고를 넘으면 안됨 (재고 0인 품절 옵션도 여기서 막힘)
        int currentQuantity = cart.findItemByOptionId(optionId)
                .map(CartItem::getQuantity)
                .orElse(0);

        if (currentQuantity + quantity > option.stock()) {
            throw new DomainException(OrderErrorCode.STOCK_EXCEEDED);
        }

        CartItem item = cart.addItem(optionId, quantity, orderPolicy.getCartMaxQuantity());

        return new CartItemAddResponse(optionId, item.getQuantity());
    }
}
