package com.backend.boundedContext.product.domain;

import com.backend.global.exception.DomainException;
import com.backend.global.jpa.entity.BaseManualIdAndTime;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static jakarta.persistence.FetchType.LAZY;

/**
 * 옵션의 재고. 옵션 하나에 하나이고, ID는 옵션 ID다. 재고는 0 이상 {@value #MAX_STOCK} 이하다.
 *
 * <p>재고를 옵션({@link ProductOption})과 다른 행에 둔다. 옵션 정보를 수정해도 재고 행은 UPDATE되지 않아
 * 주문이 바꾼 재고를 옛 값으로 덮어쓰지 않고(lost update), 재고를 바꿀 때는 이 행만 잠가서
 * 옵션 정보 수정과 서로 기다리지 않는다.
 */
@Entity
@Table(name = "option_stock")
@AttributeOverride(name = "id", column = @Column(name = "option_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OptionStock extends BaseManualIdAndTime {

    /** 재고가 이 수 이하면 구매자에게 남은 수량을 보여 준다. */
    public static final int LOW_STOCK_THRESHOLD = 5;

    /** 옵션 하나에 둘 수 있는 최대 재고. 재고 조정·복원이 int 범위를 넘지 않게 막는다. */
    public static final int MAX_STOCK = 1_000_000;

    /**
     * 재고의 옵션. ID 컬럼(option_id)을 같이 쓰므로 이 연관으로는 값을 쓰지 않는다.
     * 옵션과 1:1이지만 @OneToOne으로 매핑하면 Hibernate가 PK를 공유하는 1:1로 해석해 ID 없이 연관을 풀려다 실패하므로,
     * 같은 FK 컬럼을 읽기 전용 @ManyToOne으로 매핑한다.
     */
    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "option_id", insertable = false, updatable = false)
    private ProductOption option;

    @Column(nullable = false)
    private int stock;

    private OptionStock(ProductOption option, int stock) {
        super(option.getId());
        this.option = option;
        this.stock = stock;
    }

    /** 옵션을 만들 때 초기 재고를 넣는다. 옵션이 저장돼 ID가 있어야 한다. 초기 재고는 1 이상 {@value #MAX_STOCK} 이하다. */
    public static OptionStock init(ProductOption option, int stock) {
        if (option.getId() == null) {
            throw new IllegalArgumentException("옵션을 먼저 저장해야 재고를 만들 수 있습니다.");
        }
        if (stock < 1 || stock > MAX_STOCK) {
            throw new IllegalArgumentException("초기 재고는 1 이상 " + MAX_STOCK + " 이하여야 합니다. stock=" + stock);
        }
        return new OptionStock(option, stock);
    }

    public boolean isSoldOut() {
        return stock == 0;
    }

    /** 구매자에게 보여 줄 남은 수량. 재고가 1 ~ {@value #LOW_STOCK_THRESHOLD}개일 때만 주고, 그 외에는 null. */
    public Integer getLowStockRemaining() {
        return (stock > 0 && stock <= LOW_STOCK_THRESHOLD) ? stock : null;
    }

    /** 주문으로 재고를 뺀다. 판매할 수 없으면 409, 재고가 모자라면(0 포함) 400. */
    public void deductStock(int quantity) {
        checkQuantity(quantity);
        if (!option.isSaleable()) {
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
     * 결과가 {@value #MAX_STOCK}를 넘으면 400.
     */
    public void restoreStock(int quantity, StockChangeType changeType) {
        checkQuantity(quantity);
        if (changeType == StockChangeType.RETURN_RESTORE) {
            if (option.getProduct().isDeleted()) throw new DomainException(ProductErrorCode.PRODUCT_DELETED);
            if (option.isDeleted()) throw new DomainException(ProductErrorCode.OPTION_DELETED);
        }
        addStock(quantity);
    }

    /**
     * 판매자가 재고를 증감량만큼 조정한다. 늘리면 양수, 줄이면 음수.
     * 판매 중지·삭제된 상품이면 409(상품을 먼저 본다), 삭제된 옵션이면 409,
     * 결과가 0 미만이거나 {@value #MAX_STOCK}를 넘으면 400.
     */
    public void adjustStock(int changeQty) {
        if (changeQty == 0) {
            throw new IllegalArgumentException("증감량은 0일 수 없습니다.");
        }
        option.getProduct().checkEditable();
        if (option.isDeleted()) {
            throw new DomainException(ProductErrorCode.OPTION_DELETED);
        }
        addStock(changeQty);
    }

    /** 재고를 change만큼 바꾼다. int가 넘치지 않게 long으로 계산한다. */
    private void addStock(long change) {
        long next = stock + change;
        if (next < 0) {
            throw new DomainException(ProductErrorCode.STOCK_NEGATIVE);
        }
        if (next > MAX_STOCK) {
            throw new DomainException(ProductErrorCode.STOCK_LIMIT_EXCEEDED);
        }
        stock = (int) next;
    }

    private void checkQuantity(int quantity) {
        if (quantity < 1) {
            throw new IllegalArgumentException("수량은 1 이상이어야 합니다. quantity=" + quantity);
        }
    }
}
