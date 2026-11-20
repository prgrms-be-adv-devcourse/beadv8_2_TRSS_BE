package com.backend.boundedContext.delivery.app;

import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.delivery.domain.Delivery;
import com.backend.boundedContext.delivery.in.dto.LogisticsDeliveryResponse;
import com.backend.boundedContext.delivery.out.DeliveryRepository;
import com.backend.global.page.PageResponse;
import com.backend.shared.delivery.dto.DeliveryStatus;

import lombok.RequiredArgsConstructor;

/**
 * 물류 배송 목록
 * 한 페이지 항목의 상품 대표 이미지를 market에 한 번에 조회해 붙인다
 */
@Component
@RequiredArgsConstructor
public class DeliveryGetDeliveriesUseCase {

    private static final Sort OLDEST_PAID_FIRST = Sort.by(Sort.Order.asc("paidAt"), Sort.Order.asc("id"));
    private static final Sort LATEST = Sort.by(Sort.Order.desc("id"));

    private final DeliveryRepository deliveryRepository;
    private final DeliveryProductImageSupport deliveryProductImageSupport;

    public PageResponse<LogisticsDeliveryResponse> getDeliveries(DeliveryStatus status, Pageable pageable) {
        DeliveryStatus target = status == null ? DeliveryStatus.READY : status;
        Sort sort = target == DeliveryStatus.READY ? OLDEST_PAID_FIRST : LATEST;
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);

        Page<Delivery> deliveries = deliveryRepository.findByStatus(target, sorted);
        Map<Long, String> thumbnails = deliveryProductImageSupport.getThumbnailUrls(deliveries.getContent());
        return PageResponse.from(deliveries, delivery -> LogisticsDeliveryResponse.from(delivery, thumbnails));
    }
}
