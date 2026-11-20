package com.backend.shared.order.dto;

import java.time.LocalDateTime;

/**
 * 리뷰를 쓸 수 있는 주문 항목 (본인 주문 + 구매 확정)
 *
 * @param orderItemId 주문 항목 ID
 * @param productId   상품 ID
 * @param optionId    옵션 ID
 * @param optionName  옵션명 (주문 시점 스냅샷)
 * @param confirmedAt 구매 확정 시각
 */
public record ReviewableOrderItemDto(Long orderItemId, Long productId, Long optionId,
                                     String optionName, LocalDateTime confirmedAt) { }
