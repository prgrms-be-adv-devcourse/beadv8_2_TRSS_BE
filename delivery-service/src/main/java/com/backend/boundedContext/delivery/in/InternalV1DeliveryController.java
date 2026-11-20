package com.backend.boundedContext.delivery.in;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backend.global.rsData.RsData;
import com.backend.shared.delivery.dto.DeliveryDto;
import com.backend.shared.delivery.out.DeliveryApi;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;

/**
 * 배송 내부 API. {@link DeliveryApi}를 HTTP로 제공한다 (market DeliveryApiClient가 주문 상세에서 부름).
 * /internal/** 은 X-Internal-Key로만 부를 수 있다(SecurityConfig.internalFilterChain). 서비스끼리만 부르므로 Swagger에서 숨긴다
 */
@Hidden
@RestController
@RequestMapping("/internal/v1/deliveries")
@RequiredArgsConstructor
public class InternalV1DeliveryController {

    private final DeliveryApi deliveryApi;

    // 배송 건이 아직 없으면(결제 직후·배송지 없는 주문) 200 + data null
    @GetMapping
    public RsData<DeliveryDto> findByOrderId(@RequestParam Long orderId) {
        return RsData.of("200-1", "배송 정보를 조회했습니다.", deliveryApi.findByOrderId(orderId).orElse(null));
    }
}
