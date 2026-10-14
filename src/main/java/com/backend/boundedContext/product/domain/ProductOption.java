package com.backend.boundedContext.product.domain;

import com.backend.global.exception.DomainException;
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

    /** 주문으로 재고를 뺀다. 판매할 수 없으면 409, 재고가 모자라면(0 포함) 400. */
    public void deductStock(int quantity) {
        checkQuantity(quantity);
        if (!isSaleable()) {
            throw new DomainException(ProductErrorCode.PRODUCT_NOT_ON_SALE);
        }
        if (stock < quantity) {
            throw new DomainException(ProductErrorCode.OUT_OF_STOCK);
        }
        stock -= quantity;
    }

    /**
     * 주문 취소·반품으로 재고를 되돌린다. 상품 상태는 바꾸지 않는다 (중지된 상품도 재고만 는다).
     * 반품 복원(RETURN_RESTORE)은 삭제된 상품·옵션이면 409. 주문 취소 복원은 삭제돼도 재고를 늘린다.
     */
    public void restoreStock(int quantity, StockChangeType changeType) {
        checkQuantity(quantity);
        if (changeType == StockChangeType.RETURN_RESTORE) {
            if (product.isDeleted()) throw new DomainException(ProductErrorCode.PRODUCT_DELETED);
            if (isDeleted()) throw new DomainException(ProductErrorCode.OPTION_DELETED);
        }
        stock += quantity;
    }

    /**
     * 판매자가 재고를 증감량만큼 조정한다. 늘리면 양수, 줄이면 음수.
     * 판매 중지·삭제된 상품이면 409(상품을 먼저 본다), 삭제된 옵션이면 409, 결과가 0 미만이면 400.
     */
    public void adjustStock(int changeQty) {
        if (changeQty == 0) {
            throw new IllegalArgumentException("증감량은 0일 수 없습니다.");
        }
        product.checkEditable();
        if (isDeleted()) {
            throw new DomainException(ProductErrorCode.OPTION_DELETED);
        }
        if (stock + changeQty < 0) {
            throw new DomainException(ProductErrorCode.STOCK_NEGATIVE);
        }
        stock += changeQty;
    }

    private void checkQuantity(int quantity) {
        if (quantity < 1) {
            throw new IllegalArgumentException("수량은 1 이상이어야 합니다. quantity=" + quantity);
        }
    }
}
