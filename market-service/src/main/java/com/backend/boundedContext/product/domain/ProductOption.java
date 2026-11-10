package com.backend.boundedContext.product.domain;

import com.backend.global.jpa.entity.BaseIdAndTime;
import com.backend.shared.product.dto.ProductUnsaleableReason;
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
 * 상품 옵션. 장바구니에 담는 판매 단위(SKU)다.
 * 이번 범위에서는 상품마다 기본 옵션 1개만 만든다. 재고는 다른 행({@link OptionStock})에 둔다.
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

    private LocalDateTime deletedAt;

    /** 상품 등록 시 기본 옵션을 만든다. 재고는 옵션을 저장한 뒤 {@link OptionStock#init}으로 만든다. */
    public static ProductOption createDefault(Product product) {
        ProductOption option = new ProductOption();
        option.product = product;
        option.name = DEFAULT_NAME;
        return option;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /**
     * 판매할 수 없는 이유. 판매할 수 있으면 null. 재고와는 무관하다.
     * 여러 개에 해당하면 삭제 → 중지 → 카테고리 순으로 하나만 준다 (주문 모듈이 삭제된 대상을 먼저 거를 수 있게).
     */
    public ProductUnsaleableReason getUnsaleableReason() {
        if (product.isDeleted()) return ProductUnsaleableReason.PRODUCT_DELETED;
        if (isDeleted()) return ProductUnsaleableReason.OPTION_DELETED;
        if (product.isStopped()) return ProductUnsaleableReason.PRODUCT_STOPPED;
        if (!product.getCategory().isActive()) return ProductUnsaleableReason.CATEGORY_INACTIVE;
        return null;
    }

    public boolean isSaleable() {
        return getUnsaleableReason() == null;
    }
}
