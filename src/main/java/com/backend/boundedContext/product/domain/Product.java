package com.backend.boundedContext.product.domain;

import com.backend.global.exception.DomainException;
import com.backend.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

import static jakarta.persistence.FetchType.LAZY;

/**
 * 상품. 판매자가 등록한 "무엇을 얼마에 파는가"이며, 재고는 옵션({@link ProductOption})에 있다.
 * 판매자는 ID로만 참조한다 (회원 컨텍스트의 seller.id).
 */
@Entity
@Table(name = "product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseIdAndTime {

    @Column(nullable = false)
    private Long sellerId;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private long price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductSaleStatus status;

    @Column(length = 300)
    private String stoppedReason;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ProductStoppedBy stoppedBy;

    private LocalDateTime stoppedAt;

    @Version
    private Long version;

    private LocalDateTime deletedAt;

    /** 상품을 만든다. 카테고리는 사용 중인 소분류여야 한다. */
    public static Product create(Long sellerId, Category category, String name, String description, long price) {
        category.checkSelectable();

        Product product = new Product();
        product.sellerId = sellerId;
        product.category = category;
        product.name = name.strip();
        product.description = description.strip();
        product.price = price;
        product.status = ProductSaleStatus.ON_SALE;
        return product;
    }

    public boolean isDeleted() {
        return status == ProductSaleStatus.DELETED;
    }

    public boolean isStopped() {
        return status == ProductSaleStatus.STOPPED;
    }

    /** 구매자에게 보여 줄 수 있는 상품인지. 판매 중이고 카테고리가 사용 중이어야 한다. 품절은 따로 본다. */
    public boolean isDisplayable() {
        return status == ProductSaleStatus.ON_SALE && category.isActive();
    }

    /** 판매 중지·삭제된 상품은 수정(재고 조정 포함)할 수 없다. */
    public void checkEditable() {
        if (isStopped() || isDeleted()) {
            throw new DomainException(ProductErrorCode.PRODUCT_NOT_EDITABLE);
        }
    }
}
