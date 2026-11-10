package com.backend.boundedContext.coupon.app.dto;

import com.backend.shared.product.dto.ProductSnapshotDto;

/**
 * 협약 인플루언서가 pick한 내 판매 중 상품 (상품 쿠폰 발행 대상)
 *
 * @param thumbnailUrl 대표 이미지 URL. 없으면 null
 */
public record PickedProductDto(
        Long productId,
        String name,
        long price,
        String thumbnailUrl
) {
    public static PickedProductDto from(ProductSnapshotDto product) {
        return new PickedProductDto(product.productId(), product.name(), product.price(), product.thumbnailUrl());
    }
}
