package com.backend.boundedContext.product.domain;

import com.backend.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static jakarta.persistence.FetchType.LAZY;

/**
 * 상품 이미지. 파일은 저장소(로컬·S3)에 있고, 여기에는 저장소 안의 키만 둔다.
 * URL은 응답할 때 저장소 구현이 키로 만든다.
 */
@Entity
@Table(name = "product_image")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductImage extends BaseIdAndTime {

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, length = 300)
    private String imageKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductImageType imageType;

    /** 상세 이미지의 보여 줄 순서 (0부터). 대표 이미지는 0 */
    @Column(nullable = false)
    private int sortOrder;

    public static ProductImage thumbnail(Product product, String imageKey) {
        return of(product, imageKey, ProductImageType.THUMBNAIL, 0);
    }

    public static ProductImage detail(Product product, String imageKey, int sortOrder) {
        return of(product, imageKey, ProductImageType.DETAIL, sortOrder);
    }

    public void changeSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    private static ProductImage of(Product product, String imageKey, ProductImageType imageType, int sortOrder) {
        ProductImage image = new ProductImage();
        image.product = product;
        image.imageKey = imageKey;
        image.imageType = imageType;
        image.sortOrder = sortOrder;
        return image;
    }
}
