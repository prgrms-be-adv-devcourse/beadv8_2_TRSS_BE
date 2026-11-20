package com.backend.boundedContext.delivery.app;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.delivery.domain.Delivery;
import com.backend.boundedContext.delivery.domain.DeliveryItem;
import com.backend.boundedContext.delivery.out.ProductImagePort;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 물류 응답에 붙일 상품 대표 이미지 조회 (목록·상세·송장 등록·수정이 함께 쓴다)
 * 배송 건 여러 개의 옵션 ID를 모아 market을 한 번에 부른다. 조회에 실패하면 경고만 남기고 이미지 없이 응답한다(주문 상세와 같은 정책)
 */
@Slf4j
@Component
@RequiredArgsConstructor
class DeliveryProductImageSupport {

    private final ProductImagePort productImagePort;

    // 옵션 ID → 대표 이미지 URL. 항목이 없거나 조회에 실패하면 빈 Map
    Map<Long, String> getThumbnailUrls(Collection<Delivery> deliveries) {
        List<Long> optionIds = deliveries.stream()
                .flatMap(delivery -> delivery.getItems().stream())
                .map(DeliveryItem::getOptionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (optionIds.isEmpty()) {
            return Map.of();
        }
        try {
            return productImagePort.getThumbnailUrls(optionIds);
        } catch (RuntimeException e) {
            log.warn("상품 이미지 조회에 실패해 물류 응답을 이미지 없이 보냅니다: optionCount={}, reason={}", optionIds.size(), e.getMessage());
            return Map.of();
        }
    }
}
