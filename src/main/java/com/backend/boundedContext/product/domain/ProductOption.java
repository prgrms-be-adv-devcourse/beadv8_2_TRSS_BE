package com.backend.boundedContext.product.domain;

import com.backend.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

import static jakarta.persistence.FetchType.LAZY;

/**
 * 상품 옵션. 장바구니에 담고 재고를 세는 판매 단위(SKU)다.
 * 이번 범위에서는 상품마다 기본 옵션 1개만 만든다. 재고는 0 이상이다.
 */
@Entity
@Table(name = "product_option")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductOption extends BaseIdAndTime {

    /** 옵션이 하나뿐인 상품의 옵션 이름. 화면에서는 옵션이 하나면 이름을 숨긴다. */
    public static final String DEFAULT_NAME = "기본";

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false)
    private int stock;

    private LocalDateTime deletedAt;

    /** 상품 등록 시 기본 옵션을 만든다. 초기 재고는 1 이상이다. */
    public static ProductOption createDefault(Product product, int stock) {
        if (stock < 1) {
            throw new IllegalArgumentException("초기 재고는 1 이상이어야 합니다. stock=" + stock);
        }

        ProductOption option = new ProductOption();
        option.product = product;
        option.name = DEFAULT_NAME;
        option.stock = stock;
        return option;
    }
}
