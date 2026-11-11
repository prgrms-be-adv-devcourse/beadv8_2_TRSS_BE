package com.backend.boundedContext.product.app.dto;

import com.backend.boundedContext.product.domain.ProductDiscount;
import com.backend.boundedContext.product.domain.ProductDiscountStatus;

import java.time.LocalDateTime;

/**
 * 판매자 상품 수정 폼의 할인 한 건
 *
 * @param salePrice    할인가
 * @param discountRate 지금 정가 기준 할인율 (%, 소수점 내림)
 * @param startsAt     시작 시각
 * @param endsAt       끝 시각 (이 시각부터 정가)
 * @param status       SCHEDULED(시작 전, 수정·삭제 가능) · ACTIVE(진행 중, 종료만 가능) · ENDED(끝남)
 */
public record SellerDiscountDto(
        Long discountId,
        long salePrice,
        int discountRate,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        ProductDiscountStatus status
) {
    public static SellerDiscountDto from(ProductDiscount discount, LocalDateTime now) {
        return new SellerDiscountDto(discount.getId(), discount.getSalePrice(), discount.getDiscountRate(),
                discount.getStartsAt(), discount.getEndsAt(), discount.getStatus(now));
    }
}
