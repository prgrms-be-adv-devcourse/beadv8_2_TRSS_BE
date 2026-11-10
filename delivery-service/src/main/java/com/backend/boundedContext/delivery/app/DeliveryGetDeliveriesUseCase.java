package com.backend.boundedContext.delivery.app;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.delivery.in.dto.LogisticsDeliveryResponse;
import com.backend.boundedContext.delivery.out.DeliveryRepository;
import com.backend.global.page.PageResponse;
import com.backend.shared.delivery.dto.DeliveryStatus;

import lombok.RequiredArgsConstructor;

/**
 * 물류 배송 목록
 */
@Component
@RequiredArgsConstructor
public class DeliveryGetDeliveriesUseCase {

    private static final Sort OLDEST_PAID_FIRST = Sort.by(Sort.Order.asc("paidAt"), Sort.Order.asc("id"));
    private static final Sort LATEST = Sort.by(Sort.Order.desc("id"));

    private final DeliveryRepository deliveryRepository;

    public PageResponse<LogisticsDeliveryResponse> getDeliveries(DeliveryStatus status, Pageable pageable) {
        DeliveryStatus target = status == null ? DeliveryStatus.READY : status;
        Sort sort = target == DeliveryStatus.READY ? OLDEST_PAID_FIRST : LATEST;
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);

        return PageResponse.from(deliveryRepository.findByStatus(target, sorted), LogisticsDeliveryResponse::from);
    }
}
