package com.backend.shared.delivery.out;

import com.backend.shared.delivery.dto.DeliveryDto;

import java.util.Optional;

/**
 * 배송 컨텍스트가 다른 컨텍스트에 제공하는 내부 API.
 * 구현체: delivery-service의 DeliveryApiAdapter. market은 DeliveryApiClient(HTTP, /internal/v1/deliveries)로 부른다.
 */
public interface DeliveryApi {

    /**
     * 주문의 배송 건. 아직 없으면(결제 직후·배송지 없는 주문) empty.
     * HTTP 구현은 응답을 받지 못하면 502 EXTERNAL_SERVICE_ERROR(DomainException)를 던진다. 부르는 쪽이 잡아서 처리한다.
     */
    Optional<DeliveryDto> findByOrderId(Long orderId);
}
