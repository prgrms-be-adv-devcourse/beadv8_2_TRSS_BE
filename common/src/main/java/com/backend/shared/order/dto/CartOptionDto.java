package com.backend.shared.order.dto;

import java.time.LocalDateTime;

/**
 * 구매자 장바구니에 담긴 옵션 하나 (OrderApi.getCartOptions). 장바구니는 옵션 단위라 상품 ID는 상품 컨텍스트가 옵션으로 찾는다
 *
 * @param optionId 옵션 ID
 * @param addedAt  담은 시각
 */
public record CartOptionDto(Long optionId, LocalDateTime addedAt) {
}
