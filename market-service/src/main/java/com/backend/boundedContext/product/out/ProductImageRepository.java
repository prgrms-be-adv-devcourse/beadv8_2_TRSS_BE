package com.backend.boundedContext.product.out;

import com.backend.boundedContext.product.domain.ProductImage;
import com.backend.boundedContext.product.domain.ProductImageType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    /** 상품의 이미지 (상세 이미지는 보여 줄 순서대로) */
    List<ProductImage> findAllByProductIdAndImageTypeOrderBySortOrderAscIdAsc(Long productId, ProductImageType imageType);

    /** 여러 상품의 이미지 한 번에 (목록의 대표 이미지용) */
    List<ProductImage> findAllByProductIdInAndImageType(Collection<Long> productIds, ProductImageType imageType);
}
