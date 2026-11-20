package com.backend.boundedContext.order.domain;

import com.backend.global.exception.DomainException;
import com.backend.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 장바구니는 회원당 1개이며, 처음 상품을 담을 때 만든다.
 */
@Entity
@Table(name = "cart")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Cart extends BaseIdAndTime {

    @Column(nullable = false, unique = true)
    private Long memberId;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CartItem> items = new ArrayList<>();

    // 장바구니 생성
    public static Cart create(Long memberId) {
        Cart cart = new Cart();
        cart.memberId = memberId;

        return cart;
    }

    // 장바구니 담기
    public CartItem addItem(Long optionId, int quantity, int maxQuantity) {
        Optional<CartItem> existing = findItemByOptionId(optionId);

        if (existing.isPresent()) {
            existing.get().addQuantity(quantity, maxQuantity);
            return existing.get();
        }

        CartItem item = CartItem.create(this, optionId, quantity, maxQuantity);
        items.add(item);

        return item;
    }

    // 같은 옵션이 담긴 항목
    public Optional<CartItem> findItemByOptionId(Long optionId) {
        return items.stream()
                .filter(item -> item.getOptionId().equals(optionId))
                .findFirst();
    }

    // 장바구니 항목 조회
    public CartItem getItem(Long itemId) {
        return items.stream()
                .filter(item -> Objects.equals(item.getId(), itemId))
                .findFirst()
                .orElseThrow(() -> new DomainException(OrderErrorCode.CART_ITEM_NOT_FOUND));
    }

    // 전체 선택,해제
    public void selectAll(boolean selected) {
        items.forEach(item -> item.changeSelected(selected));
    }

    // 장바구니 항목 삭제
    public void removeItems(Collection<Long> itemIds) {
        items.removeIf(item -> itemIds.contains(item.getId()));
    }
}
