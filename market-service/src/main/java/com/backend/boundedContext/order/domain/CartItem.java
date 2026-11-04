package com.backend.boundedContext.order.domain;

import com.backend.global.exception.DomainException;
import com.backend.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 장바구니 항목
 */
@Entity
@Table(name = "cart_item",
        uniqueConstraints = @UniqueConstraint(name = "uk_cart_item_option", columnNames = {"cart_id", "option_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CartItem extends BaseIdAndTime {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id")
    private Cart cart;

    @Column(nullable = false)
    private Long optionId;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private boolean selected;

    static CartItem create(Cart cart, Long optionId, int quantity, int maxQuantity) {
        validateQuantity(quantity, maxQuantity);

        CartItem item = new CartItem();
        item.cart = cart;
        item.optionId = optionId;
        item.quantity = quantity;
        item.selected = true;

        return item;
    }

    // 장바구니 수량 합산
    void addQuantity(int quantity, int maxQuantity) {
        changeQuantity(this.quantity + quantity, maxQuantity);
    }

    // 장바구니 수량 변경
    public void changeQuantity(int quantity, int maxQuantity) {
        validateQuantity(quantity, maxQuantity);
        this.quantity = quantity;
    }

    // 선택 여부 변경
    public void changeSelected(boolean selected) {
        this.selected = selected;
    }

    // 상품 최대 수량 검증
    private static void validateQuantity(int quantity, int maxQuantity) {
        if (quantity < 1 || quantity > maxQuantity) {
            throw new DomainException(OrderErrorCode.CART_QUANTITY_INVALID);
        }
    }
}
