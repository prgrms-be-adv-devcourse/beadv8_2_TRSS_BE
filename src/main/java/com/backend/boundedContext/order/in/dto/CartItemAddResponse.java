package com.backend.boundedContext.order.in.dto;

/**
 * 장바구니 담기 응답
 *
 * @param optionId 담은 옵션 ID
 * @param quantity 수량
 */
public record CartItemAddResponse(Long optionId, int quantity) {
}
