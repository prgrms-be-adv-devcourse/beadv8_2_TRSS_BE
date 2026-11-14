package com.backend.boundedContext.product.app.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 상품 상세
 *
 * @param businessName 판매자 상호명
 * @param thumbnailUrl 대표 이미지. 이미지 기능 전까지 null
 * @param imageUrls    상세 이미지. 이미지 기능 전까지 빈 목록
 * @param price        상품 가격 (정가, 기본가). 옵션별 단가는 options의 price
 * @param salePrice    할인가 (기본가 기준). 할인 중이 아니면 null
 * @param discountRate 할인율 (%, 소수점 내림). 할인 중이 아니면 null
 * @param discountEndsAt 할인이 끝나는 시각. 할인 중이 아니면 null
 * @param soldOut      옵션이 모두 품절이면 true
 * @param optionNames  옵션 이름 (예: 색상, 사이즈). 옵션이 없는 상품이면 빈 목록
 * @param options      삭제되지 않은 옵션 조합 (ID 순)
 * @param wished       로그인한 회원이 찜한 상품이면 true. 로그인하지 않았으면 false
 */
public record ProductDetailDto(
        Long productId,
        String name,
        String description,
        long price,
        Long salePrice,
        Integer discountRate,
        LocalDateTime discountEndsAt,
        Long parentCategoryId,
        String parentCategoryName,
        Long categoryId,
        String categoryName,
        Long sellerId,
        String businessName,
        String thumbnailUrl,
        List<String> imageUrls,
        boolean soldOut,
        List<String> optionNames,
        List<ProductOptionDto> options,
        boolean wished
) {
}
